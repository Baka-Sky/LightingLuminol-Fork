package net.minecraft.server.gui;

import com.google.common.collect.Lists;
import com.mojang.logging.LogQueues;
import com.mojang.logging.LogUtils;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.border.EtchedBorder;
import javax.swing.border.TitledBorder;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import net.minecraft.DefaultUncaughtExceptionHandler;
import net.minecraft.server.dedicated.DedicatedServer;
import org.slf4j.Logger;

public class MinecraftServerGui extends JComponent {
    private static final Font MONOSPACED = new Font("Monospaced", Font.PLAIN, 12);
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TITLE = "Minecraft server";
    private static final String SHUTDOWN_TITLE = "Minecraft server - shutting down!";
    private final DedicatedServer server;
    private Thread logAppenderThread;
    private final Collection<Runnable> finalizers = Lists.newArrayList();
    private final AtomicBoolean isClosing = new AtomicBoolean();

    public static MinecraftServerGui showFrameFor(final DedicatedServer server) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception var3) {
        }

        final JFrame frame = new JFrame("Minecraft server");
        final MinecraftServerGui gui = new MinecraftServerGui(server);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.add(gui);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        // Paper start - Improve ServerGUI
        frame.setName("Minecraft server");
        try {
            frame.setIconImage(javax.imageio.ImageIO.read(java.util.Objects.requireNonNull(MinecraftServerGui.class.getClassLoader().getResourceAsStream("logo.png"))));
        } catch (java.io.IOException ignore) {
        }
        // Paper end - Improve ServerGUI
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(final WindowEvent event) {
                if (!gui.isClosing.getAndSet(true)) {
                    frame.setTitle("Minecraft server - shutting down!");
                    server.halt(true);
                    gui.runFinalizers();
                }
            }
        });
        gui.addFinalizer(frame::dispose);
        gui.start();
        return gui;
    }

    private MinecraftServerGui(final DedicatedServer server) {
        this.server = server;
        this.setPreferredSize(new Dimension(854, 480));
        this.setLayout(new BorderLayout());

        try {
            this.add(this.buildOnboardingPanel(), "North"); // Paper - Add onboarding message for initial server start
            this.add(this.buildChatPanel(), "Center");
            this.add(this.buildInfoPanel(), "West");
        } catch (Exception e) {
            LOGGER.error("Couldn't build server GUI", e);
        }
    }

    public void addFinalizer(final Runnable finalizer) {
        this.finalizers.add(finalizer);
    }

    private JComponent buildInfoPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        com.destroystokyo.paper.gui.GuiStatsComponent comp = new com.destroystokyo.paper.gui.GuiStatsComponent(this.server); // Paper - Make GUI graph fancier
        this.finalizers.add(comp::close);
        panel.add(comp, "North");
        panel.add(this.buildPlayerPanel(), "Center");
        panel.setBorder(new TitledBorder(new EtchedBorder(), "Stats"));
        return panel;
    }

    private JComponent buildPlayerPanel() {
        JList<?> playerList = new PlayerListComponent(this.server);
        JScrollPane scrollPane = new JScrollPane(playerList, ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setBorder(new TitledBorder(new EtchedBorder(), "Players"));
        return scrollPane;
    }

    private JComponent buildChatPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        JTextArea chatArea = new JTextArea();
        JScrollPane scrollPane = new JScrollPane(chatArea, ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        chatArea.setEditable(false);
        chatArea.setFont(MONOSPACED);
        JTextField chatField = new JTextField();
        chatField.addActionListener(event -> {
            String text = chatField.getText().trim();
            if (!text.isEmpty()) {
                this.server.handleConsoleInput(text, this.server.createCommandSourceStack());
            }

            chatField.setText("");
        });
        chatArea.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(final FocusEvent arg0) {
            }
        });
        panel.add(scrollPane, "Center");
        panel.add(chatField, "South");
        panel.setBorder(new TitledBorder(new EtchedBorder(), "Log and chat"));
        this.logAppenderThread = new Thread(() -> {
            String line;
            while ((line = LogQueues.getNextLogEvent("ServerGuiConsole")) != null) {
                this.print(chatArea, scrollPane, line);
            }
        }, "Server log monitor");
        this.logAppenderThread.setUncaughtExceptionHandler(new DefaultUncaughtExceptionHandler(LOGGER));
        this.logAppenderThread.setDaemon(true);
        return panel;
    }

    public void start() {
        this.logAppenderThread.start();
    }

    public void close() {
        if (!this.isClosing.getAndSet(true)) {
            this.runFinalizers();
        }
    }

    private void runFinalizers() {
        this.finalizers.forEach(Runnable::run);
    }

    private static final java.util.regex.Pattern ANSI = java.util.regex.Pattern.compile("\\e\\[[\\d;]*[^\\d;]"); // CraftBukkit // Paper
    public void print(final JTextArea console, final JScrollPane scrollPane, final String line) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> this.print(console, scrollPane, line));
        } else {
            Document document = console.getDocument();
            JScrollBar scrollBar = scrollPane.getVerticalScrollBar();
            boolean shouldScroll = false;
            if (scrollPane.getViewport().getView() == console) {
                shouldScroll = scrollBar.getValue() + scrollBar.getSize().getHeight() + MONOSPACED.getSize() * 4 > scrollBar.getMaximum();
            }

            try {
                document.insertString(document.getLength(), MinecraftServerGui.ANSI.matcher(line).replaceAll(""), null); // CraftBukkit
            } catch (BadLocationException var8) {
            }

            if (shouldScroll) {
                scrollBar.setValue(Integer.MAX_VALUE);
            }
        }
    }

    // Paper start - Add onboarding message for initial server start
    private JComponent buildOnboardingPanel() {
        String onboardingLink = "https://docs.papermc.io/paper/next-steps";
        JPanel jPanel = new JPanel();

        javax.swing.JLabel jLabel = new javax.swing.JLabel("If you need help setting up your server you can visit:");
        jLabel.setFont(MinecraftServerGui.MONOSPACED);

        javax.swing.JLabel link = new javax.swing.JLabel("<html><u> " + onboardingLink + "</u></html>");
        link.setFont(MinecraftServerGui.MONOSPACED);
        link.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        link.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(final java.awt.event.MouseEvent e) {
                try {
                    java.awt.Desktop.getDesktop().browse(java.net.URI.create(onboardingLink));
                } catch (java.io.IOException exception) {
                    LOGGER.error("Unable to find a default browser. Please manually visit the website: " + onboardingLink, exception);
                } catch (UnsupportedOperationException exception) {
                    LOGGER.error("This platform does not support the BROWSE action. Please manually visit the website: " + onboardingLink, exception);
                } catch (SecurityException exception) {
                    LOGGER.error("This action has been denied by the security manager. Please manually visit the website: " + onboardingLink, exception);
                }
            }
        });

        jPanel.add(jLabel);
        jPanel.add(link);

        return jPanel;
    }
    // Paper end - Add onboarding message for initial server start
}
