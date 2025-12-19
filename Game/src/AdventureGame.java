// AdventureGame.java
import javax.imageio.ImageIO;
import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.List;
import javax.swing.Timer;
import java.util.Stack;
public class AdventureGame extends JFrame {
    private static final int BOARD_CELLS = 64;
    private static final int STAR_TO_POINT = 5;

    private GameBoard gameBoard;
    private JPanel controlPanel;
    private JButton startButton;
    private JButton rollDiceButton;
    private JButton settingsButton;
    private JButton editAvatarButton;
    private JLabel diceResultLabel;
    private JPanel dicePanel;
    private JTextArea gameLogArea;
    private JLabel currentPlayerLabel;
    private JPanel playersInfoPanel;

    private List<Player> players;
    private Queue<Player> playerQueue;
    private Player currentPlayer;
    private boolean gameStarted = false;
    private Random random;
    private boolean isAnimating = false;
    // SCORERECORD
    private Map<String, ScoreRecord> scoreMap;
    private final File scoreFile;

    private boolean[] starsClaimed = new boolean[BOARD_CELLS + 1];
    private Set<Integer> bossNodes = new HashSet<>(Arrays.asList(8, 15, 23, 31, 42, 55));
    private int bossWinPoints = 10;
    private int bossWinStars = 2;
    private int bossLosePoints = -5;
    private int bossLoseStars = -1;

    private List<RandomLink> randomLinks = new ArrayList<>();
    private int[] tilePoints = new int[BOARD_CELLS + 1];

    private final java.util.List<Clip> runningClips = Collections.synchronizedList(new ArrayList<>());
    private Clip backgroundClip = null;

    public AdventureGame() {
        random = new Random();
        players = new ArrayList<>();
        // GILIRAN
        playerQueue = new LinkedList<>();
        scoreMap = new HashMap<>();

        String userHome = System.getProperty("user.home");
        scoreFile = new File(userHome, ".adventure_scores.ser");

        loadScores();
        initializeUI();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stopBackgroundLoop();
                synchronized (runningClips) {
                    for (Clip c : new ArrayList<>(runningClips)) {
                        try { c.stop(); c.close(); } catch (Exception ignored) {}
                    }
                    runningClips.clear();
                }
            }
        });
    }

    // ========== ScoreRecord (UNCHANGED) ==========
    public static class ScoreRecord implements Serializable {
        private static final long serialVersionUID = 1L;
        public int wins;
        public int gamesPlayed;
        public int totalStars;
        public int totalScore;

        public ScoreRecord() {
            this.wins = 0;
            this.gamesPlayed = 0;
            this.totalStars = 0;
            this.totalScore = 0;
        }

        @Override
        public String toString() {
            return "W:" + wins + " G:" + gamesPlayed + " S:" + totalStars + " P:" + totalScore;
        }
    }

    @SuppressWarnings("unchecked")
    private void loadScores() {
        if (!scoreFile.exists()) {
            scoreMap = new HashMap<>();
            return;
        }
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(scoreFile))) {
            Object obj = ois.readObject();
            if (obj instanceof Map) scoreMap = (Map<String, ScoreRecord>) obj;
            else scoreMap = new HashMap<>();
        } catch (Exception e) {
            System.err.println("Failed to load scores: " + e.getMessage());
            scoreMap = new HashMap<>();
        }
    }

    private void saveScores() {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(scoreFile))) {
            oos.writeObject(scoreMap);
        } catch (Exception e) {
            System.err.println("Failed to save scores: " + e.getMessage());
        }
    }

    private void ensureScoreRecordExists(String name) {
        if (!scoreMap.containsKey(name)) {
            scoreMap.put(name, new ScoreRecord());
            saveScores();
        }
    }

    private void updateScoresAfterMatch(Player winner) {
        for (Player p : players) {
            ScoreRecord rec = scoreMap.getOrDefault(p.getName(), new ScoreRecord());
            rec.gamesPlayed += 1;
            rec.totalStars += p.getStars();
            rec.totalScore += p.getScore();
            scoreMap.put(p.getName(), rec);
        }
        if (winner != null) {
            ScoreRecord rec = scoreMap.getOrDefault(winner.getName(), new ScoreRecord());
            rec.wins += 1;
            scoreMap.put(winner.getName(), rec);
        }
        saveScores();
    }

    private String getScoreSummary(String name) {
        ScoreRecord rec = scoreMap.get(name);
        if (rec == null) return "W:0 G:0 S:0 P:0";
        return "W:" + rec.wins + " G:" + rec.gamesPlayed + " S:" + rec.totalStars + " P:" + rec.totalScore;
    }

    // ========== UI INITIALIZATION (MINOR UPDATES) ==========
    private void initializeUI() {
        setTitle("Adventure Game — Treasure Map Edition");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));
        getContentPane().setBackground(new Color(245, 240, 230));

        add(createHeaderPanel(), BorderLayout.NORTH);

        gameBoard = new GameBoard();
        JScrollPane boardScroll = new JScrollPane(gameBoard);
        boardScroll.setBorder(null);
        boardScroll.getViewport().setBackground(new Color(245,240,230));
        add(boardScroll, BorderLayout.CENTER);

        controlPanel = createControlPanel();
        add(controlPanel, BorderLayout.EAST);

        setMinimumSize(new Dimension(1200, 820));
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        pack();
        setLocationRelativeTo(null);
    }

    private JPanel createHeaderPanel() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new EmptyBorder(14, 18, 8, 18));
        p.setOpaque(false);

        JLabel title = new JLabel("ADVENTURE — TREASURE MAP");
        title.setFont(new Font("Serif", Font.BOLD, 24));
        title.setForeground(new Color(60, 30, 10));

        JLabel subtitle = new JLabel("Normalized Coordinates • Pin Markers • Boss Encounters • Prime Ladders");
        subtitle.setFont(new Font("SansSerif", Font.PLAIN, 12));
        subtitle.setForeground(new Color(100, 80, 70));

        JPanel wrap = new JPanel();
        wrap.setOpaque(false);
        wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
        wrap.add(title);
        wrap.add(Box.createVerticalStrut(6));
        wrap.add(subtitle);

        p.add(wrap, BorderLayout.WEST);

        JLabel badge = new JLabel("Updated");
        badge.setOpaque(true);
        badge.setBackground(new Color(255, 250, 235));
        badge.setBorder(new LineBorder(new Color(200, 170, 140), 1, true));
        badge.setForeground(new Color(95, 60, 30));
        badge.setFont(new Font("SansSerif", Font.BOLD, 12));
        badge.setHorizontalAlignment(SwingConstants.CENTER);
        badge.setPreferredSize(new Dimension(88, 30));
        p.add(badge, BorderLayout.EAST);

        return p;
    }

    private JPanel createControlPanel() {
        // Main panel - VERTICAL stacking for right side
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        panel.setBackground(new Color(250, 246, 238));
        panel.setPreferredSize(new Dimension(300, 800));

        // ========== BUTTONS ==========
        startButton = createClassicButton("Start Game", new Color(210,120,60), new Color(230,160,100));
        startButton.setMaximumSize(new Dimension(280, 40));
        startButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        startButton.addActionListener(e -> startGame());
        panel.add(startButton);
        panel.add(Box.createVerticalStrut(8));

        rollDiceButton = createClassicButton("Roll Dice", new Color(95,150,210), new Color(130,190,240));
        rollDiceButton.setMaximumSize(new Dimension(280, 40));
        rollDiceButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        rollDiceButton.setEnabled(false);
        rollDiceButton.addActionListener(e -> rollDiceWithAnimation());
        panel.add(rollDiceButton);
        panel.add(Box.createVerticalStrut(8));

        settingsButton = createClassicButton("Settings", new Color(180,120,160), new Color(210,160,190));
        settingsButton.setMaximumSize(new Dimension(280, 34));
        settingsButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        settingsButton.addActionListener(e -> openSettingsDialog());
        panel.add(settingsButton);
        panel.add(Box.createVerticalStrut(6));

        editAvatarButton = createClassicButton("Edit Avatar", new Color(160,170,120), new Color(190,210,150));
        editAvatarButton.setMaximumSize(new Dimension(280, 34));
        editAvatarButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        editAvatarButton.addActionListener(e -> promptEditAvatar());
        panel.add(editAvatarButton);
        panel.add(Box.createVerticalStrut(10));

        // ========== DICE ==========
        dicePanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                g2.setColor(new Color(255, 255, 250));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            }
        };
        dicePanel.setOpaque(false);
        dicePanel.setLayout(new BoxLayout(dicePanel, BoxLayout.Y_AXIS));
        dicePanel.setBorder(new EmptyBorder(8, 8, 8, 8));
        dicePanel.setMaximumSize(new Dimension(280, 90));
        dicePanel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel diceTitle = new JLabel("Dice");
        diceTitle.setFont(new Font("Serif", Font.BOLD, 11));
        diceTitle.setForeground(new Color(80, 50, 30));
        diceTitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        diceResultLabel = new JLabel("?");
        diceResultLabel.setFont(new Font("Serif", Font.BOLD, 42));
        diceResultLabel.setForeground(new Color(80, 120, 80));
        diceResultLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        dicePanel.add(diceTitle);
        dicePanel.add(Box.createVerticalStrut(4));
        dicePanel.add(diceResultLabel);
        panel.add(dicePanel);
        panel.add(Box.createVerticalStrut(10));

        // ========== CURRENT TURN ==========
        currentPlayerLabel = new JLabel("Waiting for players...");
        currentPlayerLabel.setOpaque(true);
        currentPlayerLabel.setBackground(new Color(255, 250, 240));
        currentPlayerLabel.setBorder(new LineBorder(new Color(210, 180, 150), 1, true));
        currentPlayerLabel.setFont(new Font("Serif", Font.BOLD, 12));
        currentPlayerLabel.setForeground(new Color(80, 60, 40));
        currentPlayerLabel.setHorizontalAlignment(SwingConstants.CENTER);
        currentPlayerLabel.setMaximumSize(new Dimension(280, 28));
        currentPlayerLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(currentPlayerLabel);
        panel.add(Box.createVerticalStrut(8));

        // ========== PLAYERS ==========
        JLabel playersTitle = new JLabel("Players");
        playersTitle.setFont(new Font("Serif", Font.BOLD, 12));
        playersTitle.setForeground(new Color(85, 60, 40));
        playersTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(playersTitle);
        panel.add(Box.createVerticalStrut(4));

        playersInfoPanel = new JPanel();
        playersInfoPanel.setOpaque(false);
        playersInfoPanel.setLayout(new BoxLayout(playersInfoPanel, BoxLayout.Y_AXIS));
        JScrollPane playersScroll = new JScrollPane(playersInfoPanel);
        playersScroll.setBorder(new LineBorder(new Color(220, 200, 180), 1, true));
        playersScroll.setMaximumSize(new Dimension(280, 160));
        playersScroll.setAlignmentX(Component.CENTER_ALIGNMENT);
        playersScroll.getViewport().setBackground(new Color(250,246,238));
        panel.add(playersScroll);
        panel.add(Box.createVerticalStrut(8));

        // ========== GAME LOG ==========
        JLabel logTitle = new JLabel("Game Log");
        logTitle.setFont(new Font("Serif", Font.BOLD, 12));
        logTitle.setForeground(new Color(90, 70, 50));
        logTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(logTitle);
        panel.add(Box.createVerticalStrut(4));

        gameLogArea = new JTextArea();
        gameLogArea.setFont(new Font("Monospaced", Font.PLAIN, 10));
        gameLogArea.setEditable(false);
        gameLogArea.setLineWrap(true);
        gameLogArea.setWrapStyleWord(true);
        gameLogArea.setBackground(new Color(255, 255, 250));
        gameLogArea.setForeground(new Color(40, 30, 20));
        gameLogArea.setBorder(new EmptyBorder(6, 6, 6, 6));

        JScrollPane logScroll = new JScrollPane(gameLogArea);
        logScroll.setMaximumSize(new Dimension(280, 250));
        logScroll.setPreferredSize(new Dimension(280, 250));
        logScroll.setAlignmentX(Component.CENTER_ALIGNMENT);
        logScroll.setBorder(new LineBorder(new Color(220, 200, 180), 2, true));
        logScroll.getViewport().setBackground(new Color(255,255,250));
        panel.add(logScroll);

        panel.add(Box.createVerticalGlue());

        return panel;
    }

    private JButton createClassicButton(String text, Color top, Color bottom) {
        JButton btn = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                g2.setColor(new Color(200, 170, 140, 90));
                g2.fillRoundRect(3, 6, w - 6, h - 6, 12, 12);
                GradientPaint gp = new GradientPaint(0, 0, top, 0, h, bottom);
                if (!isEnabled()) gp = new GradientPaint(0,0,new Color(220,220,220),0,h,new Color(200,200,200));
                g2.setPaint(gp);
                g2.fillRoundRect(0, 0, w, h, 12, 12);
                g2.setColor(new Color(240, 230, 220));
                g2.setStroke(new BasicStroke(2));
                g2.drawRoundRect(1, 1, w - 2, h - 2, 12, 12);
                g2.setFont(getFont().deriveFont(Font.BOLD, 14f));
                FontMetrics fm = g2.getFontMetrics();
                g2.setColor(new Color(40, 30, 20));
                int tx = (w - fm.stringWidth(getText())) / 2;
                int ty = (h + fm.getAscent()) / 2 - 3;
                g2.drawString(getText(), tx, ty);
                g2.dispose();
            }
        };
        btn.setFont(new Font("Serif", Font.BOLD, 13));
        btn.setForeground(Color.DARK_GRAY);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setPreferredSize(new Dimension(200, 44));
        return btn;
    }

    private void updatePlayersInfoPanel() {
        playersInfoPanel.removeAll();

        class RowBuilder {
            JPanel build(Player p) {
                JPanel card = new JPanel(new BorderLayout(6, 0));
                card.setMaximumSize(new Dimension(300, 60));  // ← 80→60 (smaller height)
                card.setBackground(new Color(255, 255, 250));
                card.setBorder(BorderFactory.createCompoundBorder(
                        new LineBorder(new Color(220,200,180),1,true),
                        new EmptyBorder(4,6,4,6)));  // ← 6,8,6,8 → 4,6,4,6 (less padding)

                JPanel avatarBox = new JPanel() {
                    @Override
                    protected void paintComponent(Graphics g) {
                        super.paintComponent(g);
                        Graphics2D g2 = (Graphics2D) g;
                        if (p.getAvatar() != null) {
                            BufferedImage img = p.getAvatar();
                            int w = getWidth(), h = getHeight();
                            int iw = img.getWidth(), ih = img.getHeight();
                            double scale = Math.min((w-6)/(double)iw, (h-6)/(double)ih);  // ← 8→6
                            int dw = (int)(iw*scale), dh = (int)(ih*scale);
                            g2.drawImage(img, 3 + (w-6-dw)/2, 3 + (h-6-dh)/2, dw, dh, null);  // ← 4→3
                        } else {
                            g2.setColor(p.getColor());
                            g2.fillOval(3,3,36,36);  // ← 4,4,44,44 → 3,3,36,36 (smaller circle)
                            g2.setColor(p.getColor().darker());
                            g2.setStroke(new BasicStroke(1.5f));  // ← 2→1.5 (thinner border)
                            g2.drawOval(3,3,36,36);  // ← smaller
                            g2.setColor(new Color(20,18,12));
                            g2.setFont(new Font("Serif", Font.BOLD, 15));  // ← 18→15 (smaller font)
                            FontMetrics fm = g2.getFontMetrics();
                            String in = p.getName().substring(0,1).toUpperCase();
                            g2.drawString(in, 3 + (36 - fm.stringWidth(in))/2, 3 + (36 + fm.getAscent())/2 - 2);
                        }
                    }
                };
                avatarBox.setPreferredSize(new Dimension(48, 48));  // ← 60,56 → 48,48 (smaller avatar box)
                avatarBox.setOpaque(false);

                String history = getScoreSummary(p.getName());
                JLabel info = new JLabel("<html><b>" + p.getName() + "</b> • N" + p.getPosition() + " ★" + p.getStars()
                        + " • " + p.getScore() + "pts"  // ← Shortened text
                        + (p.isFinished() ? " • ✓" : "")  // ← "Finished" → "✓"
                        + "<br/><span style='font-size:10px;color:#6b4f36;'>" + history + "</span></html>");  // ← 11px→10px
                info.setFont(new Font("Serif", Font.PLAIN, 11));  // ← 13→11 (smaller font)
                info.setForeground(new Color(70, 50, 30));

                card.add(avatarBox, BorderLayout.WEST);
                card.add(info, BorderLayout.CENTER);

                return card;
            }
        }

        Set<String> shownNames = new HashSet<>();
        if (gameStarted && players != null && !players.isEmpty()) {
            JLabel header = new JLabel("Current Players");
            header.setFont(new Font("Serif", Font.BOLD, 14));
            header.setForeground(new Color(85, 60, 40));
            header.setBorder(new EmptyBorder(6, 4, 6, 4));
            playersInfoPanel.add(header);
            playersInfoPanel.add(Box.createVerticalStrut(6));

            RowBuilder rb = new RowBuilder();
            for (Player p : players) {
                ensureScoreRecordExists(p.getName());
                playersInfoPanel.add(rb.build(p));
                playersInfoPanel.add(Box.createVerticalStrut(6));
                shownNames.add(p.getName());
            }

            playersInfoPanel.add(Box.createVerticalStrut(8));
            JLabel histLabel = new JLabel("All Players (History)");
            histLabel.setFont(new Font("Serif", Font.BOLD, 13));
            histLabel.setForeground(new Color(85, 60, 40));
            histLabel.setBorder(new EmptyBorder(6, 4, 6, 4));
            playersInfoPanel.add(histLabel);
            playersInfoPanel.add(Box.createVerticalStrut(6));

            List<String> allNames = new ArrayList<>(scoreMap.keySet());
            for (Player p : players) if (!allNames.contains(p.getName())) allNames.add(p.getName());

            allNames.sort((a, b) -> {
                ScoreRecord ra = scoreMap.getOrDefault(a, new ScoreRecord());
                ScoreRecord rbRec = scoreMap.getOrDefault(b, new ScoreRecord());
                if (rbRec.wins != ra.wins) return Integer.compare(rbRec.wins, ra.wins);
                if (rbRec.totalStars != ra.totalStars) return Integer.compare(rbRec.totalStars, ra.totalStars);
                return a.compareToIgnoreCase(b);
            });

            for (String name : allNames) {
                if (shownNames.contains(name)) continue;
                ScoreRecord rec = scoreMap.getOrDefault(name, new ScoreRecord());

                // ========== UPDATED CODE ==========
                JPanel card = new JPanel(new BorderLayout(6, 0));
                card.setMaximumSize(new Dimension(300, 40));  // ← 48→40
                card.setBackground(new Color(255, 255, 250));
                card.setBorder(BorderFactory.createCompoundBorder(
                        new LineBorder(new Color(220,200,180),1,true),
                        new EmptyBorder(4,6,4,6)));  // ← Smaller padding

                JLabel info = new JLabel("<html><b>" + name + "</b> <span style='font-size:10px;color:#6b4f36;'>"
                        + getScoreSummary(name) + "</span></html>");  // ← 11px→10px, removed "History:" label
                info.setFont(new Font("Serif", Font.PLAIN, 11));  // ← 13→11
                info.setForeground(new Color(70, 50, 30));

                card.add(info, BorderLayout.CENTER);
                playersInfoPanel.add(card);
                playersInfoPanel.add(Box.createVerticalStrut(4));  // ← 6→4 (less spacing)
            }

            playersInfoPanel.revalidate();
            playersInfoPanel.repaint();
            return;
        }

        for (Player p : players) ensureScoreRecordExists(p.getName());

        List<String> allNames = new ArrayList<>(scoreMap.keySet());
        allNames.sort((a, b) -> {
            ScoreRecord ra = scoreMap.getOrDefault(a, new ScoreRecord());
            ScoreRecord rb = scoreMap.getOrDefault(b, new ScoreRecord());
            if (rb.wins != ra.wins) return Integer.compare(rb.wins, ra.wins);
            if (rb.totalStars != ra.totalStars) return Integer.compare(rb.totalStars, ra.totalStars);
            return a.compareToIgnoreCase(b);
        });

        JLabel lbHeader = new JLabel("Leaderboard (All Players)");
        lbHeader.setFont(new Font("Serif", Font.BOLD, 14));
        lbHeader.setForeground(new Color(85, 60, 40));
        lbHeader.setBorder(new EmptyBorder(6, 4, 6, 4));
        playersInfoPanel.add(lbHeader);
        playersInfoPanel.add(Box.createVerticalStrut(6));

        for (String name : allNames) {
            ScoreRecord rec = scoreMap.getOrDefault(name, new ScoreRecord());
            JPanel card = new JPanel(new BorderLayout(6, 0));
            card.setMaximumSize(new Dimension(300, 40));  // ← 54→44
            card.setBackground(new Color(255, 255, 250));
            card.setBorder(BorderFactory.createCompoundBorder(
                    new LineBorder(new Color(220,200,180),1,true),
                    new EmptyBorder(4,6,4,6)));  // ← Smaller padding

            JLabel info = new JLabel("<html><b>" + name + "</b> <span style='font-size:10px;color:#6b4f36;'>"
                    + "W:" + rec.wins + " G:" + rec.gamesPlayed + " S:" + rec.totalStars + " P:" + rec.totalScore
                    + "</span></html>");  // ← 12px→10px, shortened labels
            info.setFont(new Font("Serif", Font.PLAIN, 11));  // ← 13→11
            info.setForeground(new Color(70, 50, 30));

            card.add(info, BorderLayout.CENTER);
            playersInfoPanel.add(card);
            playersInfoPanel.add(Box.createVerticalStrut(4));  // ← 6→4
        }

        playersInfoPanel.revalidate();
        playersInfoPanel.repaint();
    }

    // ========== UI HELPERS (UNCHANGED) ==========
    private void openSettingsDialog() {
        JPanel panel = new JPanel(new GridLayout(0,2,8,8));
        panel.add(new JLabel("Boss nodes (comma separated):"));
        JTextField bossField = new JTextField(bossNodes.toString().replaceAll("[\\[\\] ]",""));
        panel.add(bossField);
        panel.add(new JLabel("Boss win points:"));
        JTextField winPts = new JTextField(String.valueOf(bossWinPoints));
        panel.add(winPts);
        panel.add(new JLabel("Boss win stars:"));
        JTextField winStars = new JTextField(String.valueOf(bossWinStars));
        panel.add(winStars);
        panel.add(new JLabel("Boss lose points (negative):"));
        JTextField losePts = new JTextField(String.valueOf(bossLosePoints));
        panel.add(losePts);
        panel.add(new JLabel("Boss lose stars (negative):"));
        JTextField loseStars = new JTextField(String.valueOf(bossLoseStars));
        panel.add(loseStars);

        int res = JOptionPane.showConfirmDialog(this, panel, "Settings", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res == JOptionPane.OK_OPTION) {
            try {
                String[] parts = bossField.getText().split(",");
                Set<Integer> set = new HashSet<>();
                for (String p : parts) {
                    p = p.trim(); if (p.isEmpty()) continue;
                    set.add(Integer.parseInt(p));
                }
                bossNodes = set;
                bossWinPoints = Integer.parseInt(winPts.getText().trim());
                bossWinStars = Integer.parseInt(winStars.getText().trim());
                bossLosePoints = Integer.parseInt(losePts.getText().trim());
                bossLoseStars = Integer.parseInt(loseStars.getText().trim());
                addLog("[Settings] Updated boss configuration: " + bossNodes);
                gameBoard.repaint();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Invalid settings input: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    // AVATAR
    private void promptEditAvatar() {
        if (players == null || players.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No players to edit.", "Info", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String[] names = players.stream().map(Player::getName).toArray(String[]::new);
        String sel = (String) JOptionPane.showInputDialog(this, "Select player:", "Edit Avatar", JOptionPane.PLAIN_MESSAGE, null, names, names[0]);
        if (sel == null) return;
        Player chosen = players.stream().filter(p -> p.getName().equals(sel)).findFirst().orElse(null);
        if (chosen == null) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Avatar for " + chosen.getName());
        int ch = chooser.showOpenDialog(this);
        if (ch == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            try {
                BufferedImage img = ImageIO.read(f);
                chosen.setAvatar(img);
                addLog("[Avatar] Updated avatar for " + chosen.getName());
                updatePlayersInfoPanel();
                gameBoard.repaint();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Failed to load image: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // ========== GAME LOGIC (100% UNCHANGED) ==========
    private void startGame() {
        String numPlayersStr = JOptionPane.showInputDialog(this,
                "How many players? (2-6)",
                "Number of Players",
                JOptionPane.QUESTION_MESSAGE);
        if (numPlayersStr == null) return;
        try {
            // 2-6
            int numPlayers = Integer.parseInt(numPlayersStr);
            if (numPlayers < 2 || numPlayers > 6) {
                JOptionPane.showMessageDialog(this, "Please enter 2-6 players.", "Invalid", JOptionPane.ERROR_MESSAGE);
                return;
            }

            Arrays.fill(starsClaimed, false);
            players.clear();

            Color[] colors = {
                    new Color(255, 160, 120),
                    new Color(120, 200, 180),
                    new Color(220, 160, 230),
                    new Color(255, 220, 140),
                    new Color(160, 200, 255),
                    new Color(200, 240, 180)
            };

            for (int i = 0; i < numPlayers; i++) {
                String name = JOptionPane.showInputDialog(this, "Enter name for Player " + (i + 1) + ":", "Player Name", JOptionPane.QUESTION_MESSAGE);
                if (name == null) { return; }
                if (name.trim().isEmpty()) name = "Player " + (i + 1);
                name = name.trim();

                BufferedImage avatar = null;
                int res = JOptionPane.showConfirmDialog(this, "Do you want to select an avatar image for " + name + "?", "Avatar", JOptionPane.YES_NO_OPTION);
                if (res == JOptionPane.YES_OPTION) {
                    JFileChooser chooser = new JFileChooser();
                    chooser.setDialogTitle("Select Avatar Image for " + name);
                    int ch = chooser.showOpenDialog(this);
                    if (ch == JFileChooser.APPROVE_OPTION) {
                        File f = chooser.getSelectedFile();
                        try {
                            avatar = ImageIO.read(f);
                        } catch (Exception ex) {
                            JOptionPane.showMessageDialog(this, "Failed to load image: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }

                ensureScoreRecordExists(name);
                Player p = new Player(name, colors[i]);
                if (avatar != null) p.setAvatar(avatar);
                players.add(p);
            }

            generateRandomLinks();

            for (int i = 1; i <= BOARD_CELLS; i++) {
                tilePoints[i] = (i == 1) ? 0 : (1 + random.nextInt(10));
            }

            for (Player p : players) {
                p.setPosition(1);
                p.setStars(0);
                p.setScore(0);
                p.setFinished(false);
            }

            playerQueue.clear();
            playerQueue.addAll(players);

            gameStarted = true;
            startButton.setEnabled(false);
            rollDiceButton.setEnabled(true);

            currentPlayer = playerQueue.poll();
            currentPlayerLabel.setText("Turn: " + currentPlayer.getName());

            gameBoard.setPlayers(players);
            gameBoard.setRandomLinks(randomLinks);
            gameBoard.repaint();
            updatePlayersInfoPanel();

            addLog("════ GAME STARTED — ADVENTURE ════");
            addLog("Players: " + players.size());
            for (Player p : players) addLog("  • " + p.getName() + " (" + getScoreSummary(p.getName()) + ")");
            addLog("");
            addLog("Boss nodes: " + bossNodes);
            addLog("Random ladders: " + randomLinksSummary());
            addLog("Note: Stars (multiples of 5) are collectible only once per match.");
            addLog("Important: To use a ladder, the player MUST have STARTED their turn on a PRIME number.");
            addLog("Tile points: each tile awards points on landing (1..10). Stars will be converted at end: 1★ = " + STAR_TO_POINT + " pts.");
            addLog("First turn: " + currentPlayer.getName());
            addLog("════════════════════════════════════");

            playBackgroundLoop("backsoundGame.wav");

        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Please enter a valid number.", "Invalid", JOptionPane.ERROR_MESSAGE);
        }
    }

    private String randomLinksSummary() {
        StringBuilder sb = new StringBuilder();
        for (RandomLink l : randomLinks) {
            sb.append(l.getFrom()).append("->").append(l.getTo()).append(" ");
        }
        return sb.toString().trim();
    }

    // ========== SOUND SYSTEM (UNCHANGED) ==========
    private void playSound(String filename) {
        new Thread(() -> {
            AudioInputStream audioIn = null;
            try {
                InputStream resStream = getClass().getResourceAsStream("/" + filename);
                if (resStream != null) {
                    audioIn = AudioSystem.getAudioInputStream(new BufferedInputStream(resStream));
                } else {
                    File soundFile = new File(filename);
                    if (!soundFile.exists()) {
                        return;
                    }
                    audioIn = AudioSystem.getAudioInputStream(soundFile);
                }

                AudioFormat baseFormat = audioIn.getFormat();
                AudioFormat decodedFormat = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        baseFormat.getSampleRate(),
                        16,
                        baseFormat.getChannels(),
                        baseFormat.getChannels() * 2,
                        baseFormat.getSampleRate(),
                        false
                );

                AudioInputStream din = AudioSystem.getAudioInputStream(decodedFormat, audioIn);
                DataLine.Info info = new DataLine.Info(Clip.class, decodedFormat);
                Clip clip = (Clip) AudioSystem.getLine(info);
                clip.open(din);

                runningClips.add(clip);

                clip.addLineListener(event -> {
                    if (event.getType() == LineEvent.Type.STOP || event.getType() == LineEvent.Type.CLOSE) {
                        clip.stop();
                        clip.close();
                        runningClips.remove(clip);
                    }
                });

                clip.start();
            } catch (Exception ignored) {
            } finally {
                try { if (audioIn != null) audioIn.close(); } catch (IOException ignored) {}
            }
        }).start();
    }

    private void playBackgroundLoop(String filename) {
        if (backgroundClip != null && backgroundClip.isOpen()) {
            return;
        }
        new Thread(() -> {
            AudioInputStream audioIn = null;
            try {
                InputStream resStream = getClass().getResourceAsStream("/" + filename);
                if (resStream != null) {
                    audioIn = AudioSystem.getAudioInputStream(new BufferedInputStream(resStream));
                } else {
                    File soundFile = new File(filename);
                    if (!soundFile.exists()) {
                        return;
                    }
                    audioIn = AudioSystem.getAudioInputStream(soundFile);
                }

                AudioFormat baseFormat = audioIn.getFormat();
                AudioFormat decodedFormat = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        baseFormat.getSampleRate(),
                        16,
                        baseFormat.getChannels(),
                        baseFormat.getChannels() * 2,
                        baseFormat.getSampleRate(),
                        false
                );

                AudioInputStream din = AudioSystem.getAudioInputStream(decodedFormat, audioIn);
                DataLine.Info info = new DataLine.Info(Clip.class, decodedFormat);
                Clip clip = (Clip) AudioSystem.getLine(info);
                clip.open(din);

                try {
                    FloatControl vol = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                    float dB = -10.0f;
                    vol.setValue(Math.max(vol.getMinimum(), Math.min(vol.getMaximum(), dB)));
                } catch (Exception ignore) {}

                backgroundClip = clip;
                runningClips.add(clip);

                clip.loop(Clip.LOOP_CONTINUOUSLY);
                clip.start();

            } catch (Exception ignored) {
            } finally {
                try { if (audioIn != null) audioIn.close(); } catch (IOException ignored) {}
            }
        }).start();
    }

    private void stopBackgroundLoop() {
        if (backgroundClip != null) {
            try {
                backgroundClip.stop();
                backgroundClip.close();
            } catch (Exception ignored) {}
            runningClips.remove(backgroundClip);
            backgroundClip = null;
        }
    }

    // ========== DADU DUA ARAH (100% UNCHANGED) ==========
    private void rollDiceWithAnimation() {
        if (!gameStarted || currentPlayer == null || isAnimating) return;

        rollDiceButton.setEnabled(false);
        isAnimating = true;

        playSound("crash-spin.wav");

        int finalDiceValue = random.nextInt(6) + 1;
        double probability = random.nextDouble();
        boolean isForward = probability < 0.75;

        int cycles = 10 + random.nextInt(6);
        final int[] tick = {0};

        Color finalColor = isForward ? new Color(120, 200, 140) : new Color(220, 130, 140);
        Color[] flickerColors = new Color[] { new Color(160,160,120), new Color(140,190,160), new Color(200,150,170), new Color(180,160,120) };

        Timer spinner = new Timer(70, null);
        spinner.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                tick[0]++;
                if (tick[0] < cycles) {
                    int face = 1 + random.nextInt(6);
                    diceResultLabel.setText(String.valueOf(face));
                    diceResultLabel.setForeground(flickerColors[tick[0] % flickerColors.length]);
                    dicePanel.setBackground(diceResultLabel.getForeground().brighter().brighter());
                } else {
                    spinner.stop();
                    diceResultLabel.setText(String.valueOf(finalDiceValue));
                    diceResultLabel.setForeground(finalColor);
                    dicePanel.setBackground(new Color(255, 255, 250));
                    int oldPosition = currentPlayer.getPosition();
                    // PRIME LADDER
                    boolean usePrimePower = isPrime(oldPosition);
                    addLog("┌─────────────────────");
                    addLog("│ " + currentPlayer.getName());
                    addLog("│ Position: Node " + oldPosition + (usePrimePower ? " (PRIME) — eligible for ladders." : " — not prime; ladders disabled this turn."));
                    addLog("│ Dice: " + finalDiceValue + "  (" + (isForward? "FORWARD" : "BACKWARD") + ")");

                    animateMovementWithAutoLadder(oldPosition, finalDiceValue, isForward, usePrimePower);
                }
            }
        });
        spinner.setInitialDelay(0);
        spinner.start();
    }
    // KOLEKSI BINTANG
    private boolean awardStarIfAvailable(Player p, int pos) {
        if (pos < 1 || pos > BOARD_CELLS) return false;
        if (pos % 5 != 0) return false;
        if (starsClaimed[pos]) {
            addLog("│ ✖ Star at Node " + pos + " already claimed.");
            return false;
        }
        starsClaimed[pos] = true;
        p.addStar();
        addLog("│ ⭐ " + p.getName() + " collected star at Node " + pos + "!");
        updatePlayersInfoPanel();
        return true;
    }
    // TILE POINT
    private void awardTilePoints(Player p, int pos) {
        if (pos < 1 || pos > BOARD_CELLS) return;
        int pts = tilePoints[pos];
        if (pts == 0) return;
        p.addScore(pts);
        addLog("│ ➕ " + p.getName() + " received " + pts + " pts for landing on Node " + pos + " (tile points).");
        updatePlayersInfoPanel();
    }
    /**
     * Stack-based backward movement - retraces exact path taken
     * Including going DOWN ladders that were used before
     */
    private void animateBackwardWithStack(int startPos, int steps) {
        // Check if can go back
        if (!currentPlayer.canGoBack(steps)) {
            int available = Math.max(0, currentPlayer.getMovementHistory().size() - 1);
            addLog("│ ⚠ Cannot go back " + steps + " steps (only " + available + " available)");
            steps = available;

            if (steps == 0) {
                addLog("│ Already at starting position!");
                finishTurnAfterLanding(currentPlayer.getPosition(), false);
                return;
            }
        }

        final int[] remaining = {steps};
        final int[] currentStep = {0};
        final List<Integer> pathTaken = new ArrayList<>();
        pathTaken.add(startPos);

        addLog("│ [Stack] Going back " + steps + " steps (stack size: " + currentPlayer.getMovementHistory().size() + ")");

        javax.swing.Timer t = new javax.swing.Timer(420, null);
        t.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (remaining[0] <= 0) {
                    t.stop();
                    // Landed - award points/stars at final position
                    handleLandingAfterMove(currentPlayer.getPosition(), false);
                    return;
                }

                // Pop from stack to get previous position
                Integer prevPos = currentPlayer.popPosition();
                if (prevPos == null) {
                    t.stop();
                    addLog("│ ✖ Stack empty - stopping backward movement");
                    handleLandingAfterMove(currentPlayer.getPosition(), false);
                    return;
                }

                currentStep[0]++;
                remaining[0]--;

                playSound("move.wav");

                currentPlayer.setPosition(prevPos);
                pathTaken.add(prevPos);

                gameBoard.setHighlightPath(new ArrayList<>(pathTaken));
                gameBoard.repaint();
                updatePlayersInfoPanel();

                addLog("│ Step " + currentStep[0] + ": Node " + prevPos + " [popped from stack] (left: " + remaining[0] + ")");

                if (remaining[0] == 0) {
                    t.stop();
                    handleLandingAfterMove(prevPos, false);
                }
            }
        });
        t.start();
    }
    // ANIMASI SOUND & HIGHLIGHT
    private void animateMovementWithAutoLadder(int startPos, int moves, boolean isForward, boolean usePrimePower) {
        final int[] currentStep = {0};
        final int[] remaining = {moves};
        final int[] currentPos = {startPos};
        final List<Integer> pathTaken = new ArrayList<>();
        pathTaken.add(startPos);
        final boolean[] extraPending = {false};
        if (!isForward) {
            animateBackwardWithStack(startPos, moves);
            return;  // Exit early, jangan lanjut ke bawah
        }
        javax.swing.Timer t = new javax.swing.Timer(420, null);
        t.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (remaining[0] <= 0) {
                    t.stop();
                    handleLandingAfterMove(currentPos[0], extraPending[0]);
                    return;
                }

                int next = isForward ? currentPos[0] + 1 : currentPos[0] - 1;
                if (next > BOARD_CELLS) next = BOARD_CELLS;
                if (next < 1) next = 1;
                currentPos[0] = next;
                remaining[0]--;
                currentStep[0]++;

                playSound("move.wav");

                currentPlayer.setPosition(next);
                if (isForward) {
                    currentPlayer.pushPosition(next);  // Track forward movement
                    addLog("│   [Stack] Pushed: " + next + " (size: " + currentPlayer.getMovementHistory().size() + ")");
                }
                pathTaken.add(next);
                gameBoard.setHighlightPath(new ArrayList<>(pathTaken));
                gameBoard.repaint();
                updatePlayersInfoPanel();

                addLog("│ Step " + currentStep[0] + ": Node " + next + " (left: " + remaining[0] + ")");

                if (usePrimePower && isForward && remaining[0] > 0) {
                    for (RandomLink link : randomLinks) {
                        if (link.isLadder() && link.getFrom() == currentPos[0]) {
                            final RandomLink capturedLink = link;
                            addLog("│ ✦ PRIME: Auto-using LADDER!");
                            addLog("│ Teleporting: " + capturedLink.getFrom() + " → " + capturedLink.getTo());
                            playSound("move.wav");
                            t.stop();
                            javax.swing.Timer teleport = new javax.swing.Timer(700, evt -> {
                                currentPos[0] = capturedLink.getTo();
                                currentPlayer.setPosition(capturedLink.getTo());

                                // ========== ADD THESE 2 LINES ==========
                                currentPlayer.pushPosition(capturedLink.getTo());  // Track ladder destination
                                addLog("│   [Stack] Pushed ladder destination: " + capturedLink.getTo() + " (size: " + currentPlayer.getMovementHistory().size() + ")");
                                // =======================================

                                pathTaken.add(capturedLink.getTo());
                                gameBoard.setTeleportEffect(capturedLink);
                                gameBoard.setHighlightPath(new ArrayList<>(pathTaken));
                                gameBoard.repaint();
                                updatePlayersInfoPanel();
                                if (remaining[0] == 0) {
                                    boolean awarded = awardStarIfAvailable(currentPlayer, currentPos[0]);
                                    if (awarded) extraPending[0] = true;
                                }
                                awardTilePoints(currentPlayer, currentPos[0]);

                                javax.swing.Timer cont = new javax.swing.Timer(650, ev2 -> {
                                    gameBoard.setTeleportEffect(null);
                                    if (remaining[0] > 0) t.start();
                                    else handleLandingAfterMove(currentPos[0], extraPending[0]);
                                });
                                cont.setRepeats(false); cont.start();
                            });
                            teleport.setRepeats(false); teleport.start();
                            return;
                        }
                    }
                }

                if (remaining[0] == 0) {
                    t.stop();
                    handleLandingAfterMove(currentPos[0], extraPending[0]);
                    return;
                }

                if ((isForward && currentPos[0] >= BOARD_CELLS) || (!isForward && currentPos[0] <= 1)) {
                    t.stop();
                    handleLandingAfterMove(currentPos[0], extraPending[0]);
                    return;
                }
            }
        });
        t.start();
    }

    private void handleLandingAfterMove(int landedPos, boolean extraPending) {
        addLog("│ Landed: Node " + landedPos);
        boolean awarded = awardStarIfAvailable(currentPlayer, landedPos);
        if (awarded) extraPending = true;

        awardTilePoints(currentPlayer, landedPos);

        gameBoard.setHighlightPath(null);
        gameBoard.setTeleportEffect(null);
        gameBoard.repaint();

        if (bossNodes.contains(landedPos)) {
            addLog("│ 👾 Boss is present at Node " + landedPos + " — triggering encounter.");

            final int _capLanded = landedPos;
            final boolean _capExtra = extraPending;
            final Player _capPlayer = currentPlayer;

            triggerBossEncounter(_capLanded, _capPlayer, success -> {
                if (success) {
                    finishTurnAfterLanding(_capLanded, _capExtra);
                } else {
                    int prev = Math.max(1, _capLanded - 1);
                    _capPlayer.setPosition(prev);

                    addLog("│ ❌ " + _capPlayer.getName() +
                            " failed the boss and is returned to Node " + prev + ". Turn ends.");

                    gameBoard.repaint();
                    updatePlayersInfoPanel();

                    playerQueue.add(_capPlayer);
                    Player next = pollNextActivePlayer();
                    currentPlayer = next;

                    if (currentPlayer != null)
                        currentPlayerLabel.setText("Turn: " + currentPlayer.getName());
                    else
                        currentPlayerLabel.setText("Waiting...");

                    isAnimating = false;
                    rollDiceButton.setEnabled(currentPlayer != null);

                    addLog("Next: " + (currentPlayer != null ? currentPlayer.getName() : "—"));
                    addLog("└─────────────────────");
                }
            });
        } else {
            finishTurnAfterLanding(landedPos, extraPending);
        }
    }

    private void finishTurnAfterLanding(int finalPosition, boolean extraTurn) {
        addLog("│ Final: Node " + finalPosition);
        gameBoard.setHighlightPath(null);
        gameBoard.setTeleportEffect(null);
        gameBoard.repaint();

        if (finalPosition == BOARD_CELLS) {
            currentPlayer.setFinished(true);
            addLog("│ 🎉 " + currentPlayer.getName() + " reached FINISH!");

            int notFinished = 0;
            for (Player p : players) if (!p.isFinished()) notFinished++;

            if (notFinished <= 1) {
                addLog("│ Ending match early — only " + notFinished + " player(s) still not finished.");
                Player winner = computeWinnerByPointsAndStars();
                addLog("│ Winner: " + (winner != null ? winner.getName() : "NONE"));
                updateScoresAfterMatch(winner);

                stopBackgroundLoop();

                playSound("confetti.wav");
                StringBuilder sb = new StringBuilder();
                sb.append("Match ended!\n\nFinal summary (points + stars*").append(STAR_TO_POINT).append("):\n");
                for (Player p : players) {
                    int total = p.getScore() + p.getStars() * STAR_TO_POINT;
                    sb.append(String.format(" • %s — Points: %d • Stars: %d • Total: %d\n", p.getName(), p.getScore(), p.getStars(), total));
                }
                if (winner != null) sb.append("\nWinner: ").append(winner.getName()).append("\n");
                JOptionPane.showMessageDialog(this, sb.toString(), "Match Result", JOptionPane.INFORMATION_MESSAGE);

                gameStarted = false;
                startButton.setEnabled(true);
                rollDiceButton.setEnabled(false);
                isAnimating = false;
                updatePlayersInfoPanel();
                addLog("└─────────────────────");
                return;
            } else {
                addLog("│ " + currentPlayer.getName() + " finished — " + notFinished + " player(s) remaining.");
                addLog("└─────────────────────");
                Player next = pollNextActivePlayer();
                if (next != null) {
                    currentPlayer = next;
                    currentPlayerLabel.setText("Turn: " + currentPlayer.getName());
                } else {
                    currentPlayer = null;
                    currentPlayerLabel.setText("Waiting...");
                }
                isAnimating = false;
                rollDiceButton.setEnabled(true);
                updatePlayersInfoPanel();
                return;
            }
        }
        // EXTRATURN
        if (extraTurn) {
            addLog("│ ➜ Extra turn for " + currentPlayer.getName() + " (keeps turn)");
            addLog("└─────────────────────");
            currentPlayerLabel.setText("Turn: " + currentPlayer.getName());
            isAnimating = false;
            rollDiceButton.setEnabled(true);
            updatePlayersInfoPanel();
            return;
        }

        addLog("└─────────────────────");
        playerQueue.add(currentPlayer);
        Player next = pollNextActivePlayer();
        currentPlayer = next;
        if (currentPlayer != null) currentPlayerLabel.setText("Turn: " + currentPlayer.getName());
        addLog("Next: " + (currentPlayer != null ? currentPlayer.getName() : "—"));
        addLog("");
        isAnimating = false;
        rollDiceButton.setEnabled(currentPlayer != null);
        updatePlayersInfoPanel();
    }

    private Player pollNextActivePlayer() {
        int attempts = playerQueue.size();
        while (attempts-- > 0) {
            Player p = playerQueue.poll();
            if (p == null) break;
            if (!p.isFinished()) {
                return p;
            }
        }
        return null;
    }

    private Player computeWinnerByPointsAndStars() {
        Player best = null;
        int bestVal = Integer.MIN_VALUE;
        for (Player p : players) {
            int total = p.getScore() + p.getStars() * STAR_TO_POINT;
            if (total > bestVal) {
                bestVal = total;
                best = p;
            } else if (total == bestVal) {
                if (best != null && p.getStars() > best.getStars()) best = p;
            }
        }
        return best;
    }

    // ========== BOSS ENCOUNTER SYSTEM (100% UNCHANGED) ==========
    private void triggerBossEncounter(int node, Player player, java.util.function.Consumer<Boolean> callback) {
        addLog("│ 👾 Boss encountered at Node " + node + " — " + player.getName());

        Random rnd = new Random();

        String question;
        int correctAnswer;

        int type = rnd.nextInt(5);
        switch (type) {
            case 0: {
                int a = rnd.nextInt(50) + 10;
                int b = rnd.nextInt(50) + 10;
                question = "Hitung: " + a + " + " + b;
                correctAnswer = a + b;
                break;
            }
            case 1: {
                int a = rnd.nextInt(12) + 3;
                int b = rnd.nextInt(12) + 3;
                question = "Hitung: " + a + " × " + b;
                correctAnswer = a * b;
                break;
            }
            case 2: {
                int base = rnd.nextBoolean() ? 2 : 10;
                int exp = rnd.nextInt(4) + 1;
                int value = (int) Math.pow(base, exp);
                question = "Hitung: log" + base + "(" + value + ")";
                correctAnswer = exp;
                break;
            }
            case 3: {
                int a = rnd.nextInt(6) + 3;
                int b = rnd.nextInt(6) + 3;
                int c = rnd.nextInt(6) + 3;
                question = "Keliling segitiga dengan sisi " + a + ", " + b + ", " + c;
                correctAnswer = a + b + c;
                break;
            }
            default: {
                int alas = rnd.nextInt(8) + 4;
                int tinggi = rnd.nextInt(8) + 4;
                question = "Luas segitiga siku-siku (alas=" + alas + ", tinggi=" + tinggi + ")";
                correctAnswer = (alas * tinggi) / 2;
                break;
            }
        }

        JTextField answerField = new JTextField();
        JLabel timerLabel = new JLabel("Time left: 10", SwingConstants.CENTER);
        timerLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
        timerLabel.setForeground(Color.RED);

        JPanel panel = new JPanel(new GridLayout(0, 1, 6, 6));
        panel.add(new JLabel("⚔ BOSS CHALLENGE ⚔", SwingConstants.CENTER));
        panel.add(new JLabel(question, SwingConstants.CENTER));
        panel.add(answerField);
        panel.add(timerLabel);

        JOptionPane optionPane = new JOptionPane(
                panel,
                JOptionPane.QUESTION_MESSAGE,
                JOptionPane.OK_CANCEL_OPTION,
                null,
                new String[]{"Submit"},
                "Submit"
        );

        JDialog dialog = optionPane.createDialog(this, "Boss Fight");
        dialog.setModal(true);

        final int[] timeLeft = {10};
        Timer countdown = new Timer(1000, null);

        countdown.addActionListener(e -> {
            timeLeft[0]--;
            timerLabel.setText("Time left: " + timeLeft[0]);

            if (timeLeft[0] <= 0) {
                countdown.stop();
                optionPane.setValue(JOptionPane.CLOSED_OPTION);
                dialog.dispose();
            }
        });

        countdown.start();
        dialog.setVisible(true);
        countdown.stop();

        boolean success = false;
        try {
            int given = Integer.parseInt(answerField.getText().trim());
            success = (given == correctAnswer && timeLeft[0] > 0);
        } catch (Exception ignored) {
            success = false;
        }

        if (success) {
            addLog("│ ✅ " + player.getName() + " defeated the boss! +" + bossWinPoints + " pts, +" + bossWinStars + " stars");
            player.addScore(bossWinPoints);
            for (int i = 0; i < bossWinStars; i++) player.addStar();

            updatePlayersInfoPanel();
            JOptionPane.showMessageDialog(
                    this,
                    "Victory!\nCorrect Answer: " + correctAnswer,
                    "Boss Defeated",
                    JOptionPane.INFORMATION_MESSAGE
            );
            callback.accept(true);
        } else {
            addLog("│ ❌ " + player.getName() + " failed the boss challenge.");
            player.addScore(bossLosePoints);
            player.addStar(bossLoseStars);

            updatePlayersInfoPanel();
            JOptionPane.showMessageDialog(
                    this,
                    "Defeat!\nCorrect Answer: " + correctAnswer,
                    "Boss Lost",
                    JOptionPane.WARNING_MESSAGE
            );
            callback.accept(false);
        }
    }

    private void addLog(String message) {
        gameLogArea.append(message + "\n");
        gameLogArea.setCaretPosition(gameLogArea.getDocument().getLength());
    }

    // ========== LADDER GENERATION (100% UNCHANGED) ==========
    private void generateRandomLinks() {
        randomLinks.clear();
        Set<Integer> usedEndpoints = new HashSet<>();
        int attempts = 0;
        final int TARGET = 5;
        final int MAX_ATTEMPTS = 2000;

        final int BOARD_SIZE = 8;
        java.util.function.IntUnaryOperator rowOf = pos -> {
            int nodeNumber = BOARD_CELLS - pos + 1;
            return (nodeNumber - 1) / BOARD_SIZE;
        };

        while (randomLinks.size() < TARGET && attempts < MAX_ATTEMPTS) {
            attempts++;
            int a = random.nextInt(54) + 6;
            int b = random.nextInt(54) + 6;
            if (a == b) continue;
            int from = Math.min(a, b);
            int to   = Math.max(a, b);

            if (to - from < 3) continue;

            if (usedEndpoints.contains(from) || usedEndpoints.contains(to)) continue;

            int rf = rowOf.applyAsInt(from);
            int rt = rowOf.applyAsInt(to);
            if (rf == rt) continue;

            boolean bad = false;
            for (RandomLink e : randomLinks) {
                int ef = e.getFrom();
                int et = e.getTo();
                if (ef == from && et == to) { bad = true; break; }
                if ((from < ef && ef < to && to < et) || (ef < from && from < et && et < to)) {
                    bad = true;
                    break;
                }
                if (ef == from || ef == to || et == from || et == to) { bad = true; break; }
            }
            if (bad) continue;

            randomLinks.add(new RandomLink(from, to, true));
            usedEndpoints.add(from);
            usedEndpoints.add(to);
        }

        if (randomLinks.size() < TARGET) {
            addLog("[Ladders] Could only place " + randomLinks.size() + " non-overlapping ladders (attempts: " + attempts + ").");
        }
    }

    private boolean isPrime(int n) {
        if (n <= 1) return false;
        if (n <= 3) return true;
        if (n % 2 == 0 || n % 3 == 0) return false;
        for (int i = 5; i * i <= n; i += 6) {
            if (n % i == 0 || n % (i + 2) == 0) return false;
        }
        return true;
    }

    // ========== STACK MOVEMENT (UNCHANGED) ==========
    static class Player implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String name;
        private int position;
        private Color color;
        private int stars;
        private int score;
        private transient BufferedImage avatar;
        private boolean finished = false;
        private transient Stack<Integer> movementHistory;  // Track exact path taken

        public Player(String name, Color color) {
            this.name = name;
            this.position = 1;
            this.color = color;
            this.stars = 0;
            this.score = 0;
            this.avatar = null;
            this.finished=false;
            this.movementHistory = new Stack<>();
            this.movementHistory.push(1);
        }

        public String getName() { return name; }
        public int getPosition() { return position; }
        public void setPosition(int p) { position = p; }
        public Color getColor() { return color; }
        public int getStars() { return stars; }
        public void addStar() { stars++; }
        public void addStar(int delta) { stars += delta; if (stars < 0) stars = 0; }
        public void setStars(int s) { stars = Math.max(0, s); }
        public int getScore() { return score; }
        public void addScore(int delta) { score += delta; if (score < 0) score = 0; }
        public void setScore(int s) { score = Math.max(0, s); }
        public BufferedImage getAvatar() { return avatar; }
        public void setAvatar(BufferedImage b) { avatar = b; }
        public boolean isFinished() { return finished; }
        public void setFinished(boolean f) { finished = f; }
        //STACK MOVEMENT
        public Stack<Integer> getMovementHistory() {
            return movementHistory;
        }

        public void pushPosition(int pos) {
            if (movementHistory == null) {
                movementHistory = new Stack<>();
            }
            movementHistory.push(pos);
        }

        public Integer popPosition() {
            if (movementHistory == null || movementHistory.isEmpty()) {
                return position;  // Fallback to current position
            }
            return movementHistory.pop();
        }

        public boolean canGoBack(int steps) {
            return movementHistory != null && movementHistory.size() > steps;
        }

        public void clearHistory() {
            if (movementHistory == null) {
                movementHistory = new Stack<>();
            } else {
                movementHistory.clear();
            }
            movementHistory.push(position);  // Start fresh from current position
        }
    }

    static class RandomLink implements Serializable {
        private static final long serialVersionUID = 1L;
        private int from, to;
        private boolean isLadder;
        public RandomLink(int from, int to, boolean isLadder) {
            this.from = from;
            this.to = to;
            this.isLadder = isLadder;
        }
        public int getFrom() { return from; }
        public int getTo() { return to; }
        public boolean isLadder() { return isLadder; }
    }

    // ========== GAME BOARD (MAJOR UPDATE) ==========
    class GameBoard extends JPanel {

        // ============ GENERATED COORDINATES FROM TRACKER TOOL ============
        // koordinat
        private static final double[][] NODE_COORDINATES = new double[BOARD_CELLS + 1][2];

        static {
            NODE_COORDINATES[1] = new double[]{0.076, 0.746};
            NODE_COORDINATES[2] = new double[]{0.143, 0.669};
            NODE_COORDINATES[3] = new double[]{0.115, 0.587};
            NODE_COORDINATES[4] = new double[]{0.158, 0.538};
            NODE_COORDINATES[5] = new double[]{0.080, 0.508};
            NODE_COORDINATES[6] = new double[]{0.050, 0.450};
            NODE_COORDINATES[7] = new double[]{0.062, 0.368};
            NODE_COORDINATES[8] = new double[]{0.127, 0.468};
            NODE_COORDINATES[9] = new double[]{0.189, 0.490};
            NODE_COORDINATES[10] = new double[]{0.194, 0.558};
            NODE_COORDINATES[11] = new double[]{0.203, 0.622};
            NODE_COORDINATES[12] = new double[]{0.269, 0.601};
            NODE_COORDINATES[13] = new double[]{0.301, 0.636};
            NODE_COORDINATES[14] = new double[]{0.343, 0.592};
            NODE_COORDINATES[15] = new double[]{0.387, 0.585};
            NODE_COORDINATES[16] = new double[]{0.432, 0.608};
            NODE_COORDINATES[17] = new double[]{0.484, 0.552};
            NODE_COORDINATES[18] = new double[]{0.478, 0.676};
            NODE_COORDINATES[19] = new double[]{0.411, 0.697};
            NODE_COORDINATES[20] = new double[]{0.357, 0.745};
            NODE_COORDINATES[21] = new double[]{0.303, 0.818};
            NODE_COORDINATES[22] = new double[]{0.400, 0.796};
            NODE_COORDINATES[23] = new double[]{0.478, 0.911};
            NODE_COORDINATES[24] = new double[]{0.556, 0.832};
            NODE_COORDINATES[25] = new double[]{0.464, 0.762};
            NODE_COORDINATES[26] = new double[]{0.496, 0.732};
            NODE_COORDINATES[27] = new double[]{0.585, 0.655};
            NODE_COORDINATES[28] = new double[]{0.613, 0.785};
            NODE_COORDINATES[29] = new double[]{0.729, 0.755};
            NODE_COORDINATES[30] = new double[]{0.638, 0.660};
            NODE_COORDINATES[31] = new double[]{0.604, 0.597};
            NODE_COORDINATES[32] = new double[]{0.697, 0.625};
            NODE_COORDINATES[33] = new double[]{0.779, 0.608};
            NODE_COORDINATES[34] = new double[]{0.915, 0.720};
            NODE_COORDINATES[35] = new double[]{0.871, 0.550};
            NODE_COORDINATES[36] = new double[]{0.784, 0.549};
            NODE_COORDINATES[37] = new double[]{0.911, 0.479};
            NODE_COORDINATES[38] = new double[]{0.965, 0.470};
            NODE_COORDINATES[39] = new double[]{0.846, 0.441};
            NODE_COORDINATES[40] = new double[]{0.712, 0.417};
            NODE_COORDINATES[41] = new double[]{0.658, 0.381};
            NODE_COORDINATES[42] = new double[]{0.782, 0.364};
            NODE_COORDINATES[43] = new double[]{0.871, 0.276};
            NODE_COORDINATES[44] = new double[]{0.745, 0.257};
            NODE_COORDINATES[45] = new double[]{0.824, 0.185};
            NODE_COORDINATES[46] = new double[]{0.840, 0.078};
            NODE_COORDINATES[47] = new double[]{0.488, 0.423};
            NODE_COORDINATES[48] = new double[]{0.407, 0.313};
            NODE_COORDINATES[49] = new double[]{0.365, 0.326};
            NODE_COORDINATES[50] = new double[]{0.312, 0.353};
            NODE_COORDINATES[51] = new double[]{0.313, 0.420};
            NODE_COORDINATES[52] = new double[]{0.235, 0.438};
            NODE_COORDINATES[53] = new double[]{0.148, 0.373};
            NODE_COORDINATES[54] = new double[]{0.153, 0.307};
            NODE_COORDINATES[55] = new double[]{0.235, 0.218};
            NODE_COORDINATES[56] = new double[]{0.244, 0.139};
            NODE_COORDINATES[57] = new double[]{0.305, 0.222};
            NODE_COORDINATES[58] = new double[]{0.329, 0.139};
            NODE_COORDINATES[59] = new double[]{0.414, 0.083};
            NODE_COORDINATES[60] = new double[]{0.495, 0.182};
            NODE_COORDINATES[61] = new double[]{0.597, 0.231};
            NODE_COORDINATES[62] = new double[]{0.658, 0.103};
            NODE_COORDINATES[63] = new double[]{0.581, 0.116};
            NODE_COORDINATES[64] = new double[]{0.575, 0.037};
        }

        // ============ BACKGROUND IMAGE SYSTEM ============
        private BufferedImage treasureMapImage;
        private Image scaledMapImage;
        private int lastScaledWidth = -1;
        private int lastScaledHeight = -1;

        private List<Player> players;
        private List<Integer> highlightPath;
        private RandomLink teleportEffect;
        private List<RandomLink> boardLinks = new ArrayList<>();

        private Timer animationTimer;
        private float glowPhase = 0f;
        private float bobPhase = 0f;

        public GameBoard() {
            players = new ArrayList<>();
            highlightPath = new ArrayList<>();
            teleportEffect = null;

            // Load treasure map image
            treasureMapImage = loadImageFlexible("AdventureMap.jpg");
            setPreferredSize(new Dimension(1000, 800));
            setBackground(new Color(255, 253, 249));

            animationTimer = new Timer(45, e -> {
                glowPhase += 0.03f;
                bobPhase += 0.08f;
                repaint();
            });
            animationTimer.setRepeats(true);
            animationTimer.start();
        }

        // ============ LOAD BACKGROUND IMAGE ============
        /**
         * Flexible image loader - tries multiple strategies:
         * 1. Classpath/resources (works in JAR)
         * 2. Multiple file system locations
         * 3. Graceful fallback to null (gradient background)
         */
        private BufferedImage loadImageFlexible(String filename) {
            // Strategy 1: Try classpath first (packaged resources)
            try {
                InputStream is = getClass().getResourceAsStream("/" + filename);
                if (is != null) {
                    BufferedImage img = ImageIO.read(is);
                    System.out.println("[GameBoard] ✓ Image loaded from classpath: " + filename);
                    return img;
                }
            } catch (Exception e) {
                // Not in classpath, continue to file system
            }

            // Strategy 2: Try multiple file system locations
            String[] searchPaths = {
                    filename,                    // Current directory
                    "src/" + filename,           // In src/
                    "../src/" + filename,        // Parent -> src/
                    "resources/" + filename,     // Resources folder
                    "../resources/" + filename,  // Parent -> resources/
                    "./" + filename              // Explicit current
            };

            for (String path : searchPaths) {
                try {
                    File f = new File(path);
                    if (f.exists()) {
                        BufferedImage img = ImageIO.read(f);
                        System.out.println("[GameBoard] ✓ Image loaded from: " + f.getAbsolutePath());
                        return img;
                    }
                } catch (Exception e) {
                    // Try next path
                }
            }

            // Strategy 3: Not found - log details and use fallback
            System.err.println("[GameBoard] ✗ Image not found: " + filename);
            System.err.println("[GameBoard] Searched locations:");
            for (String path : searchPaths) {
                System.err.println("  • " + new File(path).getAbsolutePath());
            }
            System.err.println("[GameBoard] → Using fallback gradient background");

            return null;
        }

        public void setPlayers(List<Player> players) {
            this.players = players;
            repaint();
        }

        public void setHighlightPath(List<Integer> path) {
            this.highlightPath = (path != null) ? new ArrayList<>(path) : new ArrayList<>();
            repaint();
        }

        public void setTeleportEffect(RandomLink effect) {
            this.teleportEffect = effect;
            repaint();
        }

        public void setRandomLinks(List<RandomLink> links) {
            this.boardLinks = (links != null) ? links : new ArrayList<>();
            repaint();
        }

        // ============ NORMALIZED COORDINATE CONVERTER ============
        private Point getCoordinatesForPosition(int position, int boardWidth, int boardHeight) {
            if (position < 1 || position > BOARD_CELLS) return null;
            double[] norm = NODE_COORDINATES[position];
            int x = (int)(norm[0] * boardWidth);
            int y = (int)(norm[1] * boardHeight);
            return new Point(x, y);
        }

        // ============ MAIN PAINT METHOD ============
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            int w = getWidth();
            int h = getHeight();

            int padding = 30;
            int boardW = w - padding * 2;
            int boardH = h - padding * 2;

            // ============ RENDER BACKGROUND IMAGE ============
            if (treasureMapImage != null) {
                // Scale image only when size changes (performance optimization)
                if (scaledMapImage == null || boardW != lastScaledWidth || boardH != lastScaledHeight) {
                    scaledMapImage = treasureMapImage.getScaledInstance(boardW, boardH, Image.SCALE_SMOOTH);
                    lastScaledWidth = boardW;
                    lastScaledHeight = boardH;
                    System.out.println("[GameBoard] Scaled treasure map to: " + boardW + "x" + boardH);
                }
                g2.drawImage(scaledMapImage, padding, padding, this);
            } else {
                // Fallback gradient if image not loaded
                GradientPaint bg = new GradientPaint(0, 0, new Color(255, 253, 248),
                        w, h, new Color(245, 240, 230));
                g2.setPaint(bg);
                g2.fillRect(0, 0, w, h);
            }

            // Translate coordinate system for easier drawing
            g2.translate(padding, padding);

            // Draw game elements
            drawLadders(g2, boardW, boardH);
            drawNodes(g2, boardW, boardH);
            drawPlayers(g2, boardW, boardH);

            g2.dispose();
        }

        // ============ DRAW LADDERS ============
        private void drawLadders(Graphics2D g2, int boardW, int boardH) {
            if (boardLinks == null) return;

            for (RandomLink link : boardLinks) {
                Point from = getCoordinatesForPosition(link.getFrom(), boardW, boardH);
                Point to = getCoordinatesForPosition(link.getTo(), boardW, boardH);
                if (from == null || to == null) continue;

                // Draw ladder line
                g2.setStroke(new BasicStroke(6, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.setColor(new Color(139, 90, 43, 200)); // Brown ladder color
                g2.drawLine(from.x, from.y, to.x, to.y);

                // Glow effect for teleport animation
                if (teleportEffect != null && teleportEffect == link) {
                    float pulse = 0.55f + 0.45f * (float)Math.sin(glowPhase * 2.0);
                    int alpha = Math.min(220, (int)(220 * pulse));
                    g2.setStroke(new BasicStroke(10, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.setColor(new Color(255, 200, 120, alpha));
                    g2.drawLine(from.x, from.y, to.x, to.y);
                }
            }
        }

        // ============ DRAW NODES WITH PIN MARKERS ============
        private void drawNodes(Graphics2D g2, int boardW, int boardH) {
            for (int i = 1; i <= BOARD_CELLS; i++) {
                Point center = getCoordinatesForPosition(i, boardW, boardH);
                if (center == null) continue;

                // Highlight path effect
                if (highlightPath != null && highlightPath.contains(i)) {
                    float scale = 1f + 0.08f * (float)Math.sin(bobPhase + i * 0.3);
                    int glowSize = (int)(28 * scale);
                    g2.setColor(new Color(255, 220, 100, 140));
                    g2.setStroke(new BasicStroke(4f));
                    g2.drawOval(center.x - glowSize/2, center.y - glowSize/2, glowSize, glowSize);
                }

                // Draw pin marker (treasure map style)
                drawPinMarker(g2, center.x, center.y, i);
            }
        }

        // ============ PIN MARKER RENDERING ============
        private void drawPinMarker(Graphics2D g2, int x, int y, int nodeNumber) {
            int pinSize = 20;

            // Shadow
            g2.setColor(new Color(0, 0, 0, 60));
            g2.fillOval(x - 6, y + 2, 12, 6);

            // Pin color based on node type
            Color pinColor;
            if (isPrime(nodeNumber)) {
                pinColor = new Color(46, 204, 113); // Green for prime
            } else if (nodeNumber % 5 == 0 && !starsClaimed[nodeNumber]) {
                pinColor = new Color(255, 215, 0); // Gold for unclaimed star
            } else if (bossNodes.contains(nodeNumber)) {
                pinColor = new Color(220, 50, 50); // Red for boss
            } else if (nodeNumber == 1) {
                pinColor = new Color(100, 180, 255); // Blue for start
            } else if (nodeNumber == BOARD_CELLS) {
                pinColor = new Color(255, 150, 50); // Orange for finish
            } else {
                pinColor = new Color(200, 180, 160); // Beige for normal
            }

            // Pin head (circle)
            g2.setColor(pinColor);
            g2.fillOval(x - pinSize/2, y - pinSize/2, pinSize, pinSize);

            // Pin border
            g2.setColor(pinColor.darker());
            g2.setStroke(new BasicStroke(2f));
            g2.drawOval(x - pinSize/2, y - pinSize/2, pinSize, pinSize);

            // Pin pointer (bottom triangle)
            int[] xPoints = {x, x - 4, x + 4};
            int[] yPoints = {y + pinSize/2 + 6, y + pinSize/2, y + pinSize/2};
            g2.setColor(pinColor.darker());
            g2.fillPolygon(xPoints, yPoints, 3);

            // Node number
            g2.setFont(new Font("SansSerif", Font.BOLD, 10));
            String numStr = String.valueOf(nodeNumber);
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(Color.WHITE);
            g2.drawString(numStr, x - fm.stringWidth(numStr)/2, y + fm.getAscent()/2 - 1);

            // Special indicators
            if (nodeNumber % 5 == 0 && !starsClaimed[nodeNumber]) {
                g2.setFont(new Font("Dialog", Font.PLAIN, 14));
                g2.setColor(new Color(255, 215, 0));
                g2.drawString("★", x - 7, y - pinSize/2 - 4);
            }

            if (bossNodes.contains(nodeNumber)) {
                g2.setFont(new Font("Dialog", Font.PLAIN, 16));
                g2.setColor(new Color(180, 60, 80));
                g2.drawString("👾", x + pinSize/2 + 2, y - 2);
            }

            // Tile points indicator
            int pts = tilePoints[nodeNumber];
            if (pts > 0) {
                g2.setFont(new Font("Dialog", Font.PLAIN, 9));
                g2.setColor(new Color(90, 65, 40));
                g2.drawString("+" + pts, x + pinSize/2 + 2, y + pinSize/2 + 4);
            }
        }

        // ============ DRAW PLAYERS ============
        private void drawPlayers(Graphics2D g2, int boardW, int boardH) {
            if (players == null) return;

            for (int i = 0; i < players.size(); i++) {
                Player p = players.get(i);
                Point base = getCoordinatesForPosition(p.getPosition(), boardW, boardH);
                if (base == null) continue;

                int tokenSize = 24;
                int offX = (i % 3 - 1) * 14;     // horizontal spread kecil
                int offY = (i / 3) * 8;         // vertikal kecil (tanpa offset besar)

                int bob = (int)(4 * Math.sin(bobPhase + i * 0.8));

                // PUSATKAN token ke node
                int px = base.x - tokenSize / 2 + offX;
                int py = base.y - tokenSize / 2 + offY - bob;


                // Shadow
                g2.setColor(new Color(0, 0, 0, 40));
                g2.fillOval(px + 4, py + 8, tokenSize, tokenSize / 2);

                // Player token
                if (p.getAvatar() != null) {
                    BufferedImage img = p.getAvatar();
                    int iw = img.getWidth(), ih = img.getHeight();
                    double scale = Math.min(tokenSize/(double)iw, tokenSize/(double)ih);
                    int dw = (int)(iw*scale), dh = (int)(ih*scale);
                    g2.drawImage(img, px + (tokenSize-dw)/2, py + (tokenSize-dh)/2, dw, dh, null);
                    g2.setColor(new Color(110, 80, 60));
                    g2.setStroke(new BasicStroke(2));
                    g2.drawOval(px, py, tokenSize, tokenSize);
                } else {
                    GradientPaint gp = new GradientPaint(px, py, p.getColor().brighter(),
                            px, py + tokenSize, p.getColor().darker());
                    g2.setPaint(gp);
                    g2.fillOval(px, py, tokenSize, tokenSize);
                    g2.setColor(new Color(110, 80, 60));
                    g2.setStroke(new BasicStroke(2));
                    g2.drawOval(px, py, tokenSize, tokenSize);

                    g2.setColor(new Color(20, 18, 12));
                    g2.setFont(new Font("Serif", Font.BOLD, 14));
                    String initial = p.getName().substring(0, 1).toUpperCase();
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(initial,
                            px + (tokenSize - fm.stringWidth(initial)) / 2,
                            py + (tokenSize + fm.getAscent()) / 2 - 2);
                }
            }
        }
    }

    // ========== MAIN ==========
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            AdventureGame g = new AdventureGame();
            g.setVisible(true);
        });
    }
}
