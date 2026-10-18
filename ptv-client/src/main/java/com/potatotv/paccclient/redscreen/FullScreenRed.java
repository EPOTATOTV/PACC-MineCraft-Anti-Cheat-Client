package com.potatotv.paccclient.redscreen;

import javax.swing.*;
import java.awt.*;

/**
 * 全屏红屏警告界面：全屏红色、置顶、永久保留（直至管理员查端解除）。
 * Swing AWT/S2 实现，独立于游戏窗口。
 * <p>桌面模式（{@code PACC_DESKTOP_SHELL=tauri}）下由 Tauri 壳渲染多显示器红屏，
 * 本类仅作为无桌面壳时的降级实现。</p>
 */
public final class FullScreenRed {

    private static JFrame frame;

    /**
     * 展示红屏警告。
     * <p>仅被检测者暂停输入；其它在线玩家收到的是公示红屏，保留键盘并带「继续」按钮。</p>
     *
     * <p>P-03：玩家可见界面不展示具体检测项与风险分，所以这里只需要脱敏账号；
     * level / cheatType / riskScore 由 {@link RedscreenReceiver} 与持久化层保存，
     * 只进 PTEID 证据链，不再传进渲染层。</p>
     */
    public static void show(String pteidMasked) {
        boolean isTarget = RedscreenReceiver.activeIsTarget();
        if (isTarget) {
            KeyboardLock.lock();
        }
        if (desktopShellManaged()) return;  // 由 Tauri 壳渲染红屏，Java 不再创建窗口
        SwingUtilities.invokeLater(() -> {
            if (frame != null) frame.dispose();
            frame = new JFrame("PACC 反作弊警告");
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            frame.setAlwaysOnTop(true);
            frame.setUndecorated(true);
            frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            frame.setBackground(Color.RED);  // 置为红

            String headline = isTarget ? "检测到违规行为" : "反作弊公示";
            String tip = isTarget
                    ? "请保持现状等待管理员远程查端。输入已暂停，勿关闭本窗口。"
                    : "本机未被判定为违规，可直接点击下方按钮继续游戏。";

            JPanel panel = new JPanel(new GridBagLayout());
            panel.setBackground(new Color(0xE0, 0x00, 0x00));
            GridBagConstraints c = new GridBagConstraints();
            c.gridwidth = GridBagConstraints.REMAINDER;
            c.insets = new Insets(14, 24, 14, 24);

            JLabel mark = new JLabel("PACC 警告");
            mark.setFont(new Font(Font.DIALOG, Font.BOLD, 56));
            mark.setForeground(Color.WHITE);

            JLabel h = new JLabel("【" + headline + "】");
            h.setFont(new Font(Font.DIALOG, Font.BOLD, 30));
            h.setForeground(Color.WHITE);

            JLabel sub = new JLabel("<html>账号：" + pteidMasked + "</html>");
            sub.setFont(new Font(Font.DIALOG, Font.PLAIN, 20));
            sub.setForeground(Color.WHITE);

            JLabel tipLabel = new JLabel(tip);
            tipLabel.setFont(new Font(Font.DIALOG, Font.PLAIN, 16));
            tipLabel.setForeground(Color.WHITE);

            panel.add(mark, c);
            panel.add(h, c);
            panel.add(sub, c);
            panel.add(tipLabel, c);

            if (!isTarget) {
                JButton cont = new JButton("继续");
                cont.setFont(new Font(Font.DIALOG, Font.BOLD, 20));
                cont.setFocusPainted(false);
                cont.addActionListener(e -> dismiss());
                panel.add(cont, c);
            }

            frame.setContentPane(panel);
            frame.setLocationByPlatform(true);
            frame.setCursor(isTarget ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
            frame.setVisible(true);
            frame.toFront();
            frame.repaint();
        });
    }

    /** 管理员查端解除后关闭红屏并恢复输入。 */
    public static void dismiss() {
        KeyboardLock.unlock();
        SwingUtilities.invokeLater(() -> {
            if (frame != null) {
                frame.dispose();
                frame = null;
            }
        });
    }

    /** 是否由 Tauri 桌面壳托管（壳渲染红屏，Java 不再创建 Swing 窗口）。 */
    private static boolean desktopShellManaged() {
        return "tauri".equalsIgnoreCase(System.getenv("PACC_DESKTOP_SHELL"));
    }
}