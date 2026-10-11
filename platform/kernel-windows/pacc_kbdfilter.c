// pacc_kbdfilter.c - 键盘类过滤（redscreen 期间暂停键盘输入，鼠标保留可用）
//
// 采用 WDK kbfiltr 的做法：作为键盘类设备栈的过滤驱动，拦截
// IOCTL_INTERNAL_KEYBOARD_CONNECT，把 kbdclass 注册的 KeyboardClassServiceCallback
// 换成自己的包装回调；锁定期间丢弃键盘输入包，未锁定则透传给原始回调。
// 本模块只暂停输入，不具备任何封禁能力。service 回调运行在 DISPATCH_LEVEL。
#include "pacc_common.h"
#include <ntstrsafe.h>
#include <ntddkbd.h>   // KEYBOARD_INPUT_DATA / PKEYBOARD_INPUT_DATA

// ---- kbdmou.h 等价定义 ----
// ntddk.h / wdm.h 不含 kbdmou.h，故按 WDK 官方字段顺序自定义等价结构。来源：
//   typedef struct _CONNECT_DATA {
//       IN PDEVICE_OBJECT ClassDeviceObject;   // 上层的 class/filter 设备对象
//       IN PVOID          ClassService;        // class service 回调
//   } CONNECT_DATA, *PCONNECT_DATA;
typedef struct _PACC_CONNECT_DATA {
    PDEVICE_OBJECT ClassDeviceObject;   // class 侧设备对象（回调第一参数）
    PVOID          ClassService;        // class service 回调；本模块替换为包装回调
} PACC_CONNECT_DATA, *PPACC_CONNECT_DATA;

// class service 回调签名（等价 kbdmou.h 的 PSERVICE_CALLBACK_ROUTINE）。
typedef VOID (*PACC_SERVICE_CALLBACK_ROUTINE)(
    PVOID DeviceObject,
    PKEYBOARD_INPUT_DATA InputDataStart,
    PKEYBOARD_INPUT_DATA InputDataEnd,
    PULONG InputDataConsumed);

#ifndef IOCTL_INTERNAL_KEYBOARD_CONNECT
// kbdmou.h：CTL_CODE(FILE_DEVICE_KEYBOARD, 0x0080, METHOD_NEITHER, FILE_ANY_ACCESS)
#define IOCTL_INTERNAL_KEYBOARD_CONNECT \
    CTL_CODE(FILE_DEVICE_KEYBOARD, 0x0080, METHOD_NEITHER, FILE_ANY_ACCESS)
#endif

// ---- 相关 GUID（按 wdmguid.h 官方值内置，避免额外头文件依赖）----
// GUID_DEVINTERFACE_KEYBOARD：键盘设备接口，用于 PnP 热插拔通知。
static const GUID PACC_GUID_DEVINTERFACE_KEYBOARD =
    { 0x884b96c3, 0x56ef, 0x11d1, { 0xbc, 0x8a, 0x00, 0xa0, 0xc9, 0x14, 0x05, 0xdd } };
// GUID_DEVICE_INTERFACE_ARRIVAL / _REMOVAL：设备接口到达 / 移除事件。
static const GUID PACC_GUID_DEVINTERFACE_ARRIVAL =
    { 0xcb3a4001, 0x46f0, 0x11d0, { 0xb0, 0x8f, 0x00, 0x60, 0x97, 0x13, 0x05, 0x3f } };
static const GUID PACC_GUID_DEVINTERFACE_REMOVAL =
    { 0xcb3a4002, 0x46f0, 0x11d0, { 0xb0, 0x8f, 0x00, 0x60, 0x97, 0x13, 0x05, 0x3f } };

#define PACC_KBD_TAG          'PkfB'   // 池标签
#define PACC_KBD_CLASS_MAX    10       // \Device\KeyboardClass0..9
#define PACC_KBD_MAX_SLOTS    16       // 过滤槽位上限（含热插拔余量）

#define PACC_KBD_REG_PATH     L"\\Registry\\Machine\\SOFTWARE\\PACC"
#define PACC_KBD_VALUE_LOCK   L"KbdLock"
#define PACC_KBD_VALUE_TOKEN  L"KbdUnlockToken"

// 过滤设备扩展：把设备对象与槽位 / 下部设备绑定，供分发与完成例程使用。
typedef struct _PACC_KBD_FILTER_EXT {
    ULONG          SlotIndex;    // 对应 g_paccKbdSlots 下标
    PDEVICE_OBJECT LowerDevice;  // 下方设备（IRP 下发目标）
} PACC_KBD_FILTER_EXT, *PPACC_KBD_FILTER_EXT;

typedef struct _PACC_KBD_SLOT {
    BOOLEAN         Attached;           // 槽位已占用（认领后即置位）
    PDEVICE_OBJECT  FilterDevice;       // 我们创建的过滤设备对象
    PDEVICE_OBJECT  LowerDevice;        // IoAttachDeviceToDeviceStack 的返回值
    PFILE_OBJECT    FileObject;         // 目标设备引用，解除时释放
    PDEVICE_OBJECT  ClassDeviceObject;  // CONNECT 时 class 侧设备对象
    PVOID           OriginalService;    // 原始 KeyboardClassServiceCallback
    PUNICODE_STRING InterfaceName;      // 设备接口符号链接名拷贝（热插拔匹配用）
} PACC_KBD_SLOT;

static PACC_KBD_SLOT g_paccKbdSlots[PACC_KBD_MAX_SLOTS];
static KSPIN_LOCK    g_paccKbdSpin;                 // 仅保护槽位表簿记
static volatile LONG g_paccKbdLocked = 0;           // 1 = 丢弃键盘输入
static volatile LONG g_paccKbdUnlockToken = 0;      // 非零时解锁须匹配
static PVOID         g_paccKbdPnpNotify = NULL;
static BOOLEAN       g_paccKbdInitialized = FALSE;

static PACC_SERVICE_CALLBACK_ROUTINE PaccKbdFindOriginal(PVOID classDeviceObject);
static VOID PaccKbdDetachSlot(ULONG index);
static NTSTATUS PaccKbdAttachDevice(PDEVICE_OBJECT target, PUNICODE_STRING name,
                                    PFILE_OBJECT fileObject);

// ---- 注册表持久化（HKLM\SOFTWARE\PACC）----

static NTSTATUS
PaccKbdOpenKey(ACCESS_MASK access, PHANDLE outKey, BOOLEAN create)
{
    UNICODE_STRING path = RTL_CONSTANT_STRING(PACC_KBD_REG_PATH);
    OBJECT_ATTRIBUTES oa;
    InitializeObjectAttributes(&oa, &path,
                               OBJ_CASE_INSENSITIVE | OBJ_KERNEL_HANDLE, NULL, NULL);
    if (create) {
        ULONG disposition = 0;
        return ZwCreateKey(outKey, access, &oa, 0, NULL, REG_OPTION_NON_VOLATILE, &disposition);
    }
    return ZwOpenKey(outKey, access, &oa);
}

static NTSTATUS
PaccKbdReadDword(PCWSTR valueName, PULONG outValue)
{
    if (valueName == NULL || outValue == NULL) return STATUS_INVALID_PARAMETER;

    HANDLE key = NULL;
    NTSTATUS status = PaccKbdOpenKey(KEY_READ, &key, FALSE);
    if (!NT_SUCCESS(status)) return status;

    UNICODE_STRING name;
    RtlInitUnicodeString(&name, (PWSTR)valueName);

    UCHAR buf[sizeof(KEY_VALUE_PARTIAL_INFORMATION) + sizeof(ULONG)];
    ULONG resultLen = 0;
    PKEY_VALUE_PARTIAL_INFORMATION info = (PKEY_VALUE_PARTIAL_INFORMATION)buf;
    status = ZwQueryValueKey(key, &name, KeyValuePartialInformation, info, sizeof(buf), &resultLen);
    if (NT_SUCCESS(status) && info->Type == REG_DWORD && info->DataLength >= sizeof(ULONG)) {
        *outValue = *(PULONG)info->Data;
    } else {
        status = STATUS_OBJECT_NAME_NOT_FOUND;
    }
    ZwClose(key);
    return status;
}

static NTSTATUS
PaccKbdWriteDword(PCWSTR valueName, ULONG value)
{
    if (valueName == NULL) return STATUS_INVALID_PARAMETER;

    HANDLE key = NULL;
    NTSTATUS status = PaccKbdOpenKey(KEY_WRITE | KEY_READ, &key, TRUE);
    if (!NT_SUCCESS(status)) return status;

    UNICODE_STRING name;
    RtlInitUnicodeString(&name, (PWSTR)valueName);
    status = ZwSetValueKey(key, &name, 0, REG_DWORD, &value, sizeof(value));
    ZwClose(key);
    return status;
}

// ---- service 回调：锁定期间丢弃键盘输入 ----
// 运行在 DISPATCH_LEVEL：只做 Interlocked 读与内存转发，不阻塞、不分配。
static VOID
PaccKbdServiceCallback(
    PVOID DeviceObject,
    PKEYBOARD_INPUT_DATA InputDataStart,
    PKEYBOARD_INPUT_DATA InputDataEnd,
    PULONG InputDataConsumed)
{
    PACC_SERVICE_CALLBACK_ROUTINE original = PaccKbdFindOriginal(DeviceObject);

    if (InterlockedCompareExchange(&g_paccKbdLocked, 0, 0) != 0) {
        // 锁定：如实回报"已消费"整段包，但不向下转发 => 键盘输入被吞掉。
        if (InputDataConsumed != NULL) {
            *InputDataConsumed = (ULONG)(InputDataEnd - InputDataStart);
        }
        return;
    }

    if (original != NULL) {
        original(DeviceObject, InputDataStart, InputDataEnd, InputDataConsumed);
    } else if (InputDataConsumed != NULL) {
        // 没有原始回调可转发时保守丢弃，避免数据卡在端口缓冲里。
        *InputDataConsumed = (ULONG)(InputDataEnd - InputDataStart);
    }
}

static PACC_SERVICE_CALLBACK_ROUTINE
PaccKbdFindOriginal(PVOID classDeviceObject)
{
    PACC_SERVICE_CALLBACK_ROUTINE fallback = NULL;
    ULONG available = 0;

    for (ULONG i = 0; i < PACC_KBD_MAX_SLOTS; i++) {
        PVOID svc = g_paccKbdSlots[i].OriginalService;
        if (svc == NULL) continue;
        if ((PVOID)g_paccKbdSlots[i].ClassDeviceObject == classDeviceObject) {
            return (PACC_SERVICE_CALLBACK_ROUTINE)svc;
        }
        fallback = (PACC_SERVICE_CALLBACK_ROUTINE)svc;
        available++;
    }
    // 仅当只有一个已连接键盘时才回退，避免多键盘场景下误转发。
    return (available == 1) ? fallback : NULL;
}

// ---- CONNECT 拦截 ----
static NTSTATUS
PaccKbdConnectCompletion(PDEVICE_OBJECT DeviceObject, PIRP Irp, PVOID Context)
{
    UNREFERENCED_PARAMETER(DeviceObject);

    if (NT_SUCCESS(Irp->IoStatus.Status)) {
        PDEVICE_OBJECT filterDev = (PDEVICE_OBJECT)Context;
        PPACC_KBD_FILTER_EXT ext = (filterDev != NULL)
            ? (PPACC_KBD_FILTER_EXT)filterDev->DeviceExtension : NULL;

        // connect data 由随栈复制的下一个栈位持有（Type3InputBuffer）。
        PIO_STACK_LOCATION next = IoGetNextIrpStackLocation(Irp);
        PPACC_CONNECT_DATA data =
            (PPACC_CONNECT_DATA)next->Parameters.DeviceIoControl.Type3InputBuffer;

        if (ext != NULL && ext->SlotIndex < PACC_KBD_MAX_SLOTS &&
            data != NULL && data->ClassService != NULL) {
            PPACC_KBD_SLOT slot = &g_paccKbdSlots[ext->SlotIndex];
            slot->ClassDeviceObject = data->ClassDeviceObject;
            slot->OriginalService = data->ClassService;
            data->ClassService = PaccKbdServiceCallback;   // 换装我方回调
        }
    }

    // 返回 STATUS_SUCCESS 且不 IoCompleteRequest：让 IRP 沿原栈继续完成。
    return STATUS_SUCCESS;
}

static NTSTATUS
PaccKbdFilterDispatch(PDEVICE_OBJECT DeviceObject, PIRP Irp)
{
    PPACC_KBD_FILTER_EXT ext = (PPACC_KBD_FILTER_EXT)DeviceObject->DeviceExtension;
    if (ext == NULL || ext->LowerDevice == NULL) {
        // 非过滤设备（例如主控制设备不会收到内部设备控制请求）。
        Irp->IoStatus.Status = STATUS_NOT_SUPPORTED;
        Irp->IoStatus.Information = 0;
        IoCompleteRequest(Irp, IO_NO_INCREMENT);
        return STATUS_NOT_SUPPORTED;
    }

    PIO_STACK_LOCATION irpSp = IoGetCurrentIrpStackLocation(Irp);

    if (irpSp->Parameters.DeviceIoControl.IoControlCode == IOCTL_INTERNAL_KEYBOARD_CONNECT) {
        // 复制栈位以便挂完成例程，在完成回调里替换 class service 回调。
        IoCopyCurrentIrpStackLocationToNext(Irp);
        IoSetCompletionRoutine(Irp, PaccKbdConnectCompletion, DeviceObject, TRUE, TRUE, TRUE);
        return IoCallDriver(ext->LowerDevice, Irp);
    }

    // 其余内部设备控制请求原样透传。
    IoSkipCurrentIrpStackLocation(Irp);
    return IoCallDriver(ext->LowerDevice, Irp);
}

// ---- 附着 / 解除 ----

static NTSTATUS
PaccKbdAttachDevice(PDEVICE_OBJECT target, PUNICODE_STRING name, PFILE_OBJECT fileObject)
{
    if (target == NULL) return STATUS_INVALID_PARAMETER;

    // 认领空槽（仅簿记；设备创建放在锁外，锁内不做可能阻塞的分配）。
    KIRQL oldIrql;
    ULONG idx = PACC_KBD_MAX_SLOTS;
    KeAcquireSpinLock(&g_paccKbdSpin, &oldIrql);
    for (ULONG i = 0; i < PACC_KBD_MAX_SLOTS; i++) {
        if (!g_paccKbdSlots[i].Attached) {
            g_paccKbdSlots[i].Attached = TRUE;   // 预留，避免并发认领
            idx = i;
            break;
        }
    }
    KeReleaseSpinLock(&g_paccKbdSpin, oldIrql);
    if (idx == PACC_KBD_MAX_SLOTS) return STATUS_INSUFFICIENT_RESOURCES;

    PDEVICE_OBJECT filterDev = NULL;
    NTSTATUS status = IoCreateDevice(g_paccDriverObject, sizeof(PACC_KBD_FILTER_EXT), NULL,
                                     FILE_DEVICE_KEYBOARD, FILE_DEVICE_SECURE_OPEN, FALSE,
                                     &filterDev);
    if (!NT_SUCCESS(status)) {
        PaccKbdDetachSlot(idx);
        return status;
    }

    PPACC_KBD_FILTER_EXT ext = (PPACC_KBD_FILTER_EXT)filterDev->DeviceExtension;
    RtlZeroMemory(ext, sizeof(*ext));
    ext->SlotIndex = idx;

    PDEVICE_OBJECT lower = IoAttachDeviceToDeviceStack(filterDev, target);
    if (lower == NULL) {
        IoDeleteDevice(filterDev);
        PaccKbdDetachSlot(idx);
        return STATUS_NO_SUCH_DEVICE;
    }
    ext->LowerDevice = lower;

    // 继承底层设备的缓冲标志，避免上下 I/O 方式不一致。
    filterDev->Flags |= (lower->Flags & (DO_BUFFERED_IO | DO_DIRECT_IO));
    filterDev->Flags &= ~DO_DEVICE_INITIALIZING;

    PUNICODE_STRING nameCopy = NULL;
    if (name != NULL && name->Buffer != NULL && name->MaximumLength != 0) {
        nameCopy = (PUNICODE_STRING)ExAllocatePoolWithTag(
            NonPagedPool, sizeof(UNICODE_STRING) + name->MaximumLength, PACC_KBD_TAG);
        if (nameCopy != NULL) {
            nameCopy->Buffer = (PWCH)((PUCHAR)nameCopy + sizeof(UNICODE_STRING));
            nameCopy->Length = 0;
            nameCopy->MaximumLength = name->MaximumLength;
            RtlCopyUnicodeString(nameCopy, name);
        }
    }

    KeAcquireSpinLock(&g_paccKbdSpin, &oldIrql);
    PPACC_KBD_SLOT slot = &g_paccKbdSlots[idx];
    slot->FilterDevice = filterDev;
    slot->LowerDevice = lower;
    slot->FileObject = fileObject;
    slot->InterfaceName = nameCopy;
    // ClassDeviceObject / OriginalService 在 CONNECT 完成时填充。
    KeReleaseSpinLock(&g_paccKbdSpin, oldIrql);

    DbgPrint("[PACC] kbd filter attached (slot %u)\n", idx);
    return STATUS_SUCCESS;
}

static NTSTATUS
PaccKbdAttachByIndex(ULONG index)
{
    WCHAR nameBuf[64];
    NTSTATUS status = RtlStringCchPrintfW(nameBuf, ARRAYSIZE(nameBuf),
                                          L"\\Device\\KeyboardClass%u", index);
    if (!NT_SUCCESS(status)) return status;

    UNICODE_STRING name;
    RtlInitUnicodeString(&name, nameBuf);

    PFILE_OBJECT fileObject = NULL;
    PDEVICE_OBJECT target = NULL;
    status = IoGetDeviceObjectPointer(&name, FILE_READ_DATA, &fileObject, &target);
    if (!NT_SUCCESS(status)) return status;   // 该索引未使用

    status = PaccKbdAttachDevice(target, &name, fileObject);
    if (!NT_SUCCESS(status)) ObDereferenceObject(fileObject);
    return status;
}

static NTSTATUS
PaccKbdAttachByLink(PUNICODE_STRING symbolicLink)
{
    if (symbolicLink == NULL || symbolicLink->Buffer == NULL) return STATUS_INVALID_PARAMETER;

    PFILE_OBJECT fileObject = NULL;
    PDEVICE_OBJECT target = NULL;
    NTSTATUS status = IoGetDeviceObjectPointer(symbolicLink, FILE_READ_DATA, &fileObject, &target);
    if (!NT_SUCCESS(status)) return status;

    status = PaccKbdAttachDevice(target, symbolicLink, fileObject);
    if (!NT_SUCCESS(status)) ObDereferenceObject(fileObject);
    return status;
}

static VOID
PaccKbdDetachSlot(ULONG index)
{
    if (index >= PACC_KBD_MAX_SLOTS) return;

    KIRQL oldIrql;
    KeAcquireSpinLock(&g_paccKbdSpin, &oldIrql);
    PACC_KBD_SLOT slot = g_paccKbdSlots[index];   // 拷出后锁外解除
    RtlZeroMemory(&g_paccKbdSlots[index], sizeof(PACC_KBD_SLOT));
    KeReleaseSpinLock(&g_paccKbdSpin, oldIrql);

    // 依据 IoAttachDeviceToDeviceStack 文档：先释放 file object 引用，再脱链。
    if (slot.FileObject != NULL) ObDereferenceObject(slot.FileObject);
    if (slot.FilterDevice != NULL) {
        if (slot.LowerDevice != NULL) IoDetachDevice(slot.LowerDevice);
        IoDeleteDevice(slot.FilterDevice);
    }
    if (slot.InterfaceName != NULL) ExFreePoolWithTag(slot.InterfaceName, PACC_KBD_TAG);
}

static VOID
PaccKbdDetachByLink(PUNICODE_STRING symbolicLink)
{
    for (ULONG i = 0; i < PACC_KBD_MAX_SLOTS; i++) {
        PUNICODE_STRING stored = g_paccKbdSlots[i].InterfaceName;
        if (stored != NULL && stored->Buffer != NULL &&
            RtlEqualUnicodeString(stored, symbolicLink, TRUE)) {
            PaccKbdDetachSlot(i);
            return;
        }
    }
}

// ---- PnP 热插拔（回调运行在 PASSIVE_LEVEL）----
static NTSTATUS
PaccKbdPnpNotification(PVOID NotificationStructure, PVOID Context)
{
    UNREFERENCED_PARAMETER(Context);

    PDEVICE_INTERFACE_CHANGE_NOTIFICATION change =
        (PDEVICE_INTERFACE_CHANGE_NOTIFICATION)NotificationStructure;
    if (change == NULL || change->SymbolicLinkName == NULL) return STATUS_SUCCESS;

    if (IsEqualGUID(&change->Event, &PACC_GUID_DEVINTERFACE_ARRIVAL)) {
        PaccKbdAttachByLink(change->SymbolicLinkName);
    } else if (IsEqualGUID(&change->Event, &PACC_GUID_DEVINTERFACE_REMOVAL)) {
        PaccKbdDetachByLink(change->SymbolicLinkName);
    }
    return STATUS_SUCCESS;
}

// ---- 对外接口 ----

NTSTATUS
PaccKbdFilterInitialize(VOID)
{
    if (g_paccKbdInitialized) return STATUS_SUCCESS;
    if (g_paccDriverObject == NULL) {
        DbgPrint("[PACC] kbd filter: driver object unavailable\n");
        return STATUS_INVALID_PARAMETER;
    }

    KeInitializeSpinLock(&g_paccKbdSpin);
    RtlZeroMemory(g_paccKbdSlots, sizeof(g_paccKbdSlots));

    // 接管内部设备控制分发（主控制设备不使用该 major，安全）。
    g_paccDriverObject->MajorFunction[IRP_MJ_INTERNAL_DEVICE_CONTROL] = PaccKbdFilterDispatch;

    // 恢复持久化锁状态：重启后不丢。
    ULONG lockVal = 0;
    ULONG tokenVal = 0;
    NTSTATUS status = PaccKbdReadDword(PACC_KBD_VALUE_LOCK, &lockVal);
    if (!NT_SUCCESS(status)) DbgPrint("[PACC] kbd filter: read KbdLock failed %x\n", status);
    status = PaccKbdReadDword(PACC_KBD_VALUE_TOKEN, &tokenVal);
    if (!NT_SUCCESS(status)) DbgPrint("[PACC] kbd filter: read KbdUnlockToken failed %x\n", status);
    if (lockVal != 0) {
        InterlockedExchange(&g_paccKbdUnlockToken, (LONG)tokenVal);
        InterlockedExchange(&g_paccKbdLocked, 1);
        DbgPrint("[PACC] kbd filter: restored lock (token=%u)\n", tokenVal);
    }

    // 附着已存在的 \Device\KeyboardClass0..9。
    for (ULONG i = 0; i < PACC_KBD_CLASS_MAX; i++) {
        PaccKbdAttachByIndex(i);
    }

    // 覆盖运行中接入的键盘。仅监听后续到达，避免与固定名重复附着。
    status = IoRegisterPlugPlayNotification(
        EventCategoryDeviceInterfaceChange,
        0,
        (PVOID)&PACC_GUID_DEVINTERFACE_KEYBOARD,
        g_paccDriverObject,
        PaccKbdPnpNotification,
        NULL,
        &g_paccKbdPnpNotify);
    if (!NT_SUCCESS(status)) {
        DbgPrint("[PACC] kbd filter: IoRegisterPlugPlayNotification failed %x\n", status);
        g_paccKbdPnpNotify = NULL;
    }

    g_paccKbdInitialized = TRUE;
    DbgPrint("[PACC] kbd filter ready\n");
    return STATUS_SUCCESS;
}

VOID
PaccKbdFilterUninitialize(VOID)
{
    if (!g_paccKbdInitialized) return;

    // 先注销通知，避免解除过程中再被回调重挂。
    if (g_paccKbdPnpNotify != NULL) {
        IoUnregisterPlugPlayNotification(g_paccKbdPnpNotify);
        g_paccKbdPnpNotify = NULL;
    }

    // 写回当前锁状态（失败仅记录，不阻断卸载）。
    ULONG lockVal = (InterlockedCompareExchange(&g_paccKbdLocked, 0, 0) != 0) ? 1 : 0;
    ULONG tokenVal = (ULONG)InterlockedCompareExchange(&g_paccKbdUnlockToken, 0, 0);
    if (!NT_SUCCESS(PaccKbdWriteDword(PACC_KBD_VALUE_LOCK, lockVal))) {
        DbgPrint("[PACC] kbd filter: persist KbdLock failed\n");
    }
    if (!NT_SUCCESS(PaccKbdWriteDword(PACC_KBD_VALUE_TOKEN, tokenVal))) {
        DbgPrint("[PACC] kbd filter: persist KbdUnlockToken failed\n");
    }

    for (ULONG i = 0; i < PACC_KBD_MAX_SLOTS; i++) PaccKbdDetachSlot(i);

    g_paccKbdInitialized = FALSE;
    DbgPrint("[PACC] kbd filter uninitialized\n");
}

VOID
PaccKbdSetLock(BOOLEAN lock, ULONG token, NTSTATUS *outStatus)
{
    NTSTATUS status = STATUS_SUCCESS;

    if (!lock) {
        // 持非零解锁令牌时，解锁必须匹配，否则拒绝且不改状态。
        ULONG held = (ULONG)InterlockedCompareExchange(&g_paccKbdUnlockToken, 0, 0);
        if (held != 0 && token != held) {
            status = STATUS_ACCESS_DENIED;
        }
    }

    if (NT_SUCCESS(status)) {
        if (lock) {
            if (token != 0) InterlockedExchange(&g_paccKbdUnlockToken, (LONG)token);
            InterlockedExchange(&g_paccKbdLocked, 1);
        } else {
            InterlockedExchange(&g_paccKbdLocked, 0);
            InterlockedExchange(&g_paccKbdUnlockToken, 0);   // 解锁后令牌作废
        }

        // 立即持久化：即便非正常掉电，重启后仍保持锁定态。
        if (!NT_SUCCESS(PaccKbdWriteDword(PACC_KBD_VALUE_LOCK, lock ? 1 : 0))) {
            DbgPrint("[PACC] kbd filter: write KbdLock failed\n");
        }
        ULONG tokenVal = (ULONG)InterlockedCompareExchange(&g_paccKbdUnlockToken, 0, 0);
        if (!NT_SUCCESS(PaccKbdWriteDword(PACC_KBD_VALUE_TOKEN, tokenVal))) {
            DbgPrint("[PACC] kbd filter: write KbdUnlockToken failed\n");
        }
    }

    if (outStatus != NULL) *outStatus = status;
}

BOOLEAN
PaccKbdIsLocked(VOID)
{
    return (InterlockedCompareExchange(&g_paccKbdLocked, 0, 0) != 0);
}