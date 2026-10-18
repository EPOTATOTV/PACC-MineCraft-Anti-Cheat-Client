// pacc_introspect.c - 内核自省：IDT 完整性与内核回调注册枚举
//
// 两个只读查询，均由 IOCTL 在 PASSIVE_LEVEL 触发：
//   1) PaccIdtQuery      —— 读 IDTR，逐条目核对处理程序是否落在某个已加载内核模块内。
//   2) PaccCallbackQuery —— 定位内核通知回调数组，统计已注册项数并解析归属驱动名。
//
// 约束：只读、不改写任何内核结构，因而与 PatchGuard 兼容。
// 定位/读取失败时一律回 Supported=FALSE，绝不返回 0 冒充「没有」（文档 §9 注意事项 2）。
#include "pacc_common.h"
#include <ntstrsafe.h>
#include <intrin.h>

// ============================================================================
// IDT 完整性
// ============================================================================

// IDTR：__sidt 写入 10 字节（2 字节限长 + 8 字节基址），必须紧凑布局。
#pragma pack(push, 1)
typedef struct _PACC_IDTR {
    USHORT  Limit;
    ULONG64 Base;
} PACC_IDTR;
#pragma pack(pop)

// x64 中断门描述符（KIDTENTRY64 等价定义，16 字节）。
typedef struct _PACC_IDT_ENTRY {
    USHORT OffsetLow;
    USHORT Selector;
    USHORT IstAndFlags;     // IstIndex:3 Reserved:5 Type:5 Dpl:2 Present:1
    USHORT OffsetMiddle;
    ULONG  OffsetHigh;
    ULONG  Reserved;
} PACC_IDT_ENTRY, *PPACC_IDT_ENTRY;

#define PACC_IDT_ENTRY_COUNT  256
#define PACC_IDT_PRESENT_BIT  0x8000

static ULONG64
PaccIdtEntryHandler(PPACC_IDT_ENTRY entry)
{
    return (ULONG64)entry->OffsetLow
         | ((ULONG64)entry->OffsetMiddle << 16)
         | ((ULONG64)entry->OffsetHigh << 32);
}

// 正常 IDT 的处理程序都落在 ntoskrnl 映像内（x64 的 KiStartSystemVectorTable 等）。
// 因此「处理程序解析不到任何已加载模块」是 IDT 被劫持的强指示，且无需基线快照、无版本假设。
NTSTATUS
PaccIdtQuery(PPACC_IDT_STATUS out)
{
    if (out == NULL) return STATUS_INVALID_PARAMETER;
    RtlZeroMemory(out, sizeof(*out));
    out->FirstAnomalyIndex = 0xFFFFFFFF;

    PACC_IDTR idtr;
    RtlZeroMemory(&idtr, sizeof(idtr));
    __sidt(&idtr);

    if (idtr.Base == 0 || idtr.Limit < sizeof(PACC_IDT_ENTRY)) {
        DbgPrint("[PACC] IDT: 无效 IDTR (base=%p limit=%u)\n", (PVOID)(ULONG_PTR)idtr.Base, idtr.Limit);
        return STATUS_SUCCESS;   // Supported 保持 FALSE
    }

    ULONG capacity = ((ULONG)idtr.Limit + 1) / sizeof(PACC_IDT_ENTRY);
    if (capacity > PACC_IDT_ENTRY_COUNT) capacity = PACC_IDT_ENTRY_COUNT;

    out->IdtBase  = (ULONG64)idtr.Base;
    out->IdtLimit = (ULONG64)idtr.Limit;

    ULONG present = 0;
    ULONG anomaly = 0;

    __try {
        PPACC_IDT_ENTRY table = (PPACC_IDT_ENTRY)(ULONG_PTR)idtr.Base;
        for (ULONG i = 0; i < capacity; i++) {
            PPACC_IDT_ENTRY entry = &table[i];
            if ((entry->IstAndFlags & PACC_IDT_PRESENT_BIT) == 0) continue;

            present++;

            ULONG64 handler = PaccIdtEntryHandler(entry);
            PVOID moduleBase = NULL;
            if (handler != 0) {
                RtlPcToFileHeader((PVOID)(ULONG_PTR)handler, &moduleBase);
            }
            if (handler == 0 || moduleBase == NULL) {
                anomaly++;
                if (out->FirstAnomalyIndex == 0xFFFFFFFF) {
                    out->FirstAnomalyIndex  = i;
                    out->FirstAnomalyTarget = handler;
                }
            }
        }
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        // IDT 不可读：明确「不可用」，不猜。
        out->Supported = FALSE;
        return STATUS_SUCCESS;
    }

    if (present == 0) {
        out->Supported = FALSE;
        return STATUS_SUCCESS;
    }

    out->Supported    = TRUE;
    out->EntryCount   = present;
    out->AnomalyCount = anomaly;
    return STATUS_SUCCESS;
}

// ============================================================================
// 内核回调注册枚举
// ============================================================================

// 通知回调数组按惯例容量为 64；这里按 64 读并做越界保护。
#define PACC_CALLBACK_ARRAY_MAX    64
// 注册例程函数体内 RIP 相对 LEA 一般出现在前部的字节数窗口。
#define PACC_CALLBACK_SCAN_BYTES   0x400

static BOOLEAN
PaccAddrInRange(PVOID ptr, PVOID base, SIZE_T size)
{
    return ((ULONG_PTR)ptr >= (ULONG_PTR)base) &&
           ((ULONG_PTR)ptr <  (ULONG_PTR)base + size);
}

// 取内核映像（ntoskrnl）基址与大小，用于校验候选数组落在映像内。
static BOOLEAN
PaccKernelImageRange(PVOID *baseOut, SIZE_T *sizeOut)
{
    UNICODE_STRING routine = RTL_CONSTANT_STRING(L"ZwClose");
    PVOID addr = MmGetSystemRoutineAddress(&routine);
    if (addr == NULL) return FALSE;

    PVOID imageBase = NULL;
    RtlPcToFileHeader(addr, &imageBase);
    if (imageBase == NULL) return FALSE;

    PIMAGE_NT_HEADERS nt = RtlImageNtHeader(imageBase);
    if (nt == NULL) return FALSE;

    *baseOut = imageBase;
    *sizeOut = nt->OptionalHeader.SizeOfImage;
    return TRUE;
}

// 由模块基址反查模块名（遍历 PsLoadedModuleList，按 DllBase 匹配）。
static BOOLEAN
PaccModuleNameByBase(PVOID base, PWCHAR dst, ULONG cchDst)
{
    if (base == NULL || dst == NULL || cchDst == 0) return FALSE;
    dst[0] = L'\0';
    if (PsLoadedModuleList == NULL) return FALSE;

    __try {
        PLIST_ENTRY head = PsLoadedModuleList;
        for (PLIST_ENTRY link = head->Flink; link != head; link = link->Flink) {
            PKLDR_DATA_TABLE_ENTRY mod =
                CONTAINING_RECORD(link, KLDR_DATA_TABLE_ENTRY, InLoadOrderLinks);
            if (mod->DllBase == base) {
                PaccCopyUnicode(&mod->BaseDllName, dst, cchDst);
                break;
            }
        }
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        return FALSE;
    }
    return (dst[0] != L'\0');
}

// 归为「可疑」的口径：非 ntoskrnl 自身、也非本驱动注册的回调。
// 这是启发式信号（正规安全软件也会注册回调），由用户态与其它信号联合判定，单独不构成结论。
static BOOLEAN
PaccCallbackIsSuspicious(PVOID moduleBase, PCWSTR moduleName)
{
    if (moduleBase == NULL) return FALSE;
    if (g_paccDriverObject != NULL && moduleBase == g_paccDriverObject->DriverStart) {
        return FALSE;   // 本驱动
    }
    if (moduleName != NULL && PaccStrEqualCI(moduleName, L"ntoskrnl.exe")) {
        return FALSE;   // 系统基线
    }
    return TRUE;
}

// 校验候选地址是否像「以 NULL 结尾的通知回调指针数组」。
// 约束足够严苛，避免把随机数据当成数组：整体落在 ntoskrnl 内、8 字节对齐、
// 不越出映像末尾、至少一项、且每项都能解析到某个已加载模块。
static BOOLEAN
PaccValidateNotifyArray(PVOID candidate, PVOID imageBase, SIZE_T imageSize, PULONG pCount)
{
    if (!PaccAddrInRange(candidate, imageBase, imageSize)) return FALSE;
    if (((ULONG_PTR)candidate & (sizeof(PVOID) - 1)) != 0) return FALSE;
    if ((ULONG_PTR)candidate + (ULONG_PTR)PACC_CALLBACK_ARRAY_MAX * sizeof(PVOID)
        > (ULONG_PTR)imageBase + imageSize) {
        return FALSE;
    }

    ULONG count = 0;
    __try {
        PVOID *slots = (PVOID *)candidate;
        for (ULONG i = 0; i < PACC_CALLBACK_ARRAY_MAX; i++) {
            PVOID routine = slots[i];
            if (routine == NULL) break;
            // Ex 回调的低 3 位被用作标志位，解析归属模块前先掩掉。
            ULONG_PTR target = (ULONG_PTR)routine & ~(ULONG_PTR)0x7;
            PVOID moduleBase = NULL;
            RtlPcToFileHeader((PVOID)target, &moduleBase);
            if (moduleBase == NULL) return FALSE;   // 有项解析不到模块：候选不成立
            count++;
        }
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        return FALSE;
    }

    if (count == 0) return FALSE;   // 空数组无法与随机数据区分
    *pCount = count;
    return TRUE;
}

// 定位注册例程内第一条指向「合法通知回调数组」的 RIP 相对 LEA，并回填该类统计。
static BOOLEAN
PaccEnumerateNotifyArray(PCWSTR setterName, PPACC_CALLBACK_CLASS cls, PULONG pSuspicious)
{
    cls->Supported = FALSE;

    UNICODE_STRING name;
    RtlInitUnicodeString(&name, (PWSTR)setterName);
    PVOID setter = MmGetSystemRoutineAddress(&name);
    if (setter == NULL) return FALSE;

    PVOID  imageBase = NULL;
    SIZE_T imageSize = 0;
    if (!PaccKernelImageRange(&imageBase, &imageSize)) return FALSE;

    PUCHAR start = (PUCHAR)setter;
    PUCHAR end   = start + PACC_CALLBACK_SCAN_BYTES;

    for (PUCHAR p = start; p + 7 <= end; p++) {
        // REX.W（0x48 / 0x4C）+ 8D + ModRM(mod=00, rm=101 即 RIP 相对)
        if ((p[0] != 0x48 && p[0] != 0x4C) || p[1] != 0x8D) continue;
        if ((p[2] & 0xC7) != 0x05) continue;

        LONG disp;
        __try {
            disp = *(LONG *)(p + 3);
        } __except (EXCEPTION_EXECUTE_HANDLER) {
            return FALSE;
        }
        ULONG_PTR target = (ULONG_PTR)(p + 7) + (ULONG_PTR)(LONG_PTR)disp;

        ULONG count = 0;
        if (!PaccValidateNotifyArray((PVOID)target, imageBase, imageSize, &count)) continue;

        ULONG named = 0;
        ULONG suspicious = 0;
        __try {
            PVOID *slots = (PVOID *)target;
            for (ULONG i = 0; i < count; i++) {
                ULONG_PTR routine = (ULONG_PTR)slots[i] & ~(ULONG_PTR)0x7;
                PVOID moduleBase = NULL;
                RtlPcToFileHeader((PVOID)routine, &moduleBase);

                WCHAR moduleName[PACC_CALLBACK_NAME_CCH];
                moduleName[0] = L'\0';
                if (moduleBase != NULL) {
                    PaccModuleNameByBase(moduleBase, moduleName, PACC_CALLBACK_NAME_CCH);
                }
                if (PaccCallbackIsSuspicious(moduleBase, moduleName)) suspicious++;

                if (named < PACC_CALLBACK_NAMES_PER_CLASS) {
                    if (moduleName[0] != L'\0') {
                        RtlStringCchCopyW(cls->DriverNames[named], PACC_CALLBACK_NAME_CCH, moduleName);
                    }
                    named++;
                }
            }
        } __except (EXCEPTION_EXECUTE_HANDLER) {
            cls->Supported = FALSE;
            return FALSE;
        }

        cls->Supported  = TRUE;
        cls->ArrayVa    = (ULONG64)target;
        cls->Registered = count;
        cls->Named      = named;
        *pSuspicious += suspicious;
        return TRUE;
    }
    return FALSE;
}

NTSTATUS
PaccCallbackQuery(PPACC_CALLBACK_REPORT out)
{
    if (out == NULL) return STATUS_INVALID_PARAMETER;
    RtlZeroMemory(out, sizeof(*out));

    ULONG total = 0;
    ULONG suspicious = 0;

    PPACC_CALLBACK_CLASS processCls = &out->Classes[PACC_CALLBACK_CLASS_PROCESS];
    if (PaccEnumerateNotifyArray(L"PsSetCreateProcessNotifyRoutineEx", processCls, &suspicious)) {
        out->ClassSupported |= (1u << PACC_CALLBACK_CLASS_PROCESS);
        total += processCls->Registered;
    }

    PPACC_CALLBACK_CLASS imageCls = &out->Classes[PACC_CALLBACK_CLASS_IMAGE];
    if (PaccEnumerateNotifyArray(L"PsSetLoadImageNotifyRoutine", imageCls, &suspicious)) {
        out->ClassSupported |= (1u << PACC_CALLBACK_CLASS_IMAGE);
        total += imageCls->Registered;
    }

    // 注册表（CmRegisterCallbackEx）与对象（ObRegisterCallbacks）回调不是「全局数组 + 计数」结构，
    // 其链表头随内核版本迁移，靠模式匹配去认的误报代价高于收益。这两类保持 Supported=FALSE：
    // 用户态据此标记「未知」，而不是把 0 当成「没注册」。
    out->Classes[PACC_CALLBACK_CLASS_REGISTRY].Supported = FALSE;
    out->Classes[PACC_CALLBACK_CLASS_OBJECT].Supported   = FALSE;

    out->TotalRegistered = total;
    out->Suspicious      = suspicious;
    return STATUS_SUCCESS;
}
