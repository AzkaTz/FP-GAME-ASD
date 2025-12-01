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

/**
 * AdventureGame - Full single-file implementation (updated)
 * - Avatar selection
 * - Boss encounters (trigger only when landing)
 * - Ladders (random) — can only be used if the player STARTED the turn on a PRIME
 * - Persistent score records (~/.adventure_scores.ser)
 * - Dice animation + sound hooks
 * - Prime tiles highlighted (dark brown)
 * - Random points assigned to each tile (1..10) and awarded on landing (tile points persist across match)
 * - Winner determined by points + stars*STAR_TO_POINT
 *
 * + Background music loop feature: backsoundGame.wav
 *
 * Usage:
 *   javac AdventureGame.java
 *   java AdventureGame
 *
 * Optional sound files in working dir or resources: crash-spin.wav, move.wav, confetti.wav, backsoundGame.wav
 */
public class AdventureGame extends JFrame {
    private static final int BOARD_CELLS = 64;
    private static final int STAR_TO_POINT = 5; // conversion: 1 star = 5 points

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

    // persistence
    private Map<String, ScoreRecord> scoreMap;
    private final File scoreFile;

    // stars claimed per match: indices 1..64
    private boolean[] starsClaimed = new boolean[BOARD_CELLS + 1];

    // boss nodes (configurable)
    private Set<Integer> bossNodes = new HashSet<>(Arrays.asList(8, 15, 23, 31, 42, 55));
    private int bossWinPoints = 10;
    private int bossWinStars = 2;
    private int bossLosePoints = -5;
    private int bossLoseStars = -1;

    // random ladders
    private List<RandomLink> randomLinks = new ArrayList<>();

    // per-tile points (1..10 per tile)
    private int[] tilePoints = new int[BOARD_CELLS + 1];

    // running audio clips ref
    private final java.util.List<Clip> runningClips = Collections.synchronizedList(new ArrayList<>());

    // background loop clip (kept separate for easy stop)
    private Clip backgroundClip = null;

    public AdventureGame() {
        random = new Random();
        players = new ArrayList<>();
        playerQueue = new LinkedList<>();
        scoreMap = new HashMap<>();

        String userHome = System.getProperty("user.home");
        scoreFile = new File(userHome, ".adventure_scores.ser");

        loadScores();
        initializeUI();

        // ensure audio resources are stopped when window closes
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stopBackgroundLoop();
                // stop any running clips
                synchronized (runningClips) {
                    for (Clip c : new ArrayList<>(runningClips)) {
                        try { c.stop(); c.close(); } catch (Exception ignored) {}
                    }
                    runningClips.clear();
                }
            }
        });
    }

    // ---------- ScoreRecord ----------
    public static class ScoreRecord implements Serializable {
        private static final long serialVersionUID = 1L;
        public int wins;
        public int gamesPlayed;
        public int totalStars;
        public int totalScore;

        public ScoreRecord() { this.wins = 0; this.gamesPlayed = 0; this.totalStars = 0; this.totalScore = 0; }

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

    // ---------- UI ----------
    private void initializeUI() {
        setTitle("Adventure Game — Board Adventure");
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
        add(controlPanel, BorderLayout.SOUTH);

        setMinimumSize(new Dimension(1200, 820));
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        pack();
        setLocationRelativeTo(null);
    }

    private JPanel createHeaderPanel() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new EmptyBorder(14, 18, 8, 18));
        p.setOpaque(false);

        JLabel title = new JLabel("ADVENTURE — BOARD GAME");
        title.setFont(new Font("Serif", Font.BOLD, 24));
        title.setForeground(new Color(60, 30, 10));

        JLabel subtitle = new JLabel("Light Mode • Persistent scores • Avatars • Boss encounters • Ladders (prime rule)");
        subtitle.setFont(new Font("SansSerif", Font.PLAIN, 12));
        subtitle.setForeground(new Color(100, 80, 70));

        JPanel wrap = new JPanel();
        wrap.setOpaque(false);
        wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
        wrap.add(title);
        wrap.add(Box.createVerticalStrut(6));
        wrap.add(subtitle);

        p.add(wrap, BorderLayout.WEST);

        JLabel badge = new JLabel("Vintage");
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
        JPanel panel = new JPanel(new BorderLayout(12, 12));
        panel.setBorder(new EmptyBorder(12, 14, 12, 14));
        panel.setBackground(new Color(250, 246, 238));

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));

        startButton = createClassicButton("Start Game", new Color(210,120,60), new Color(230,160,100));
        startButton.setMaximumSize(new Dimension(220, 48));
        startButton.addActionListener(e -> startGame());
        left.add(startButton);
        left.add(Box.createVerticalStrut(10));

        rollDiceButton = createClassicButton("Roll Dice", new Color(95,150,210), new Color(130,190,240));
        rollDiceButton.setMaximumSize(new Dimension(220, 48));
        rollDiceButton.setEnabled(false);
        rollDiceButton.addActionListener(e -> rollDiceWithAnimation());
        left.add(rollDiceButton);
        left.add(Box.createVerticalStrut(10));

        settingsButton = createClassicButton("Settings", new Color(180,120,160), new Color(210,160,190));
        settingsButton.setMaximumSize(new Dimension(220, 40));
        settingsButton.addActionListener(e -> openSettingsDialog());
        left.add(settingsButton);
        left.add(Box.createVerticalStrut(8));

        editAvatarButton = createClassicButton("Edit Avatar", new Color(160,170,120), new Color(190,210,150));
        editAvatarButton.setMaximumSize(new Dimension(220, 40));
        editAvatarButton.addActionListener(e -> promptEditAvatar());
        left.add(editAvatarButton);
        left.add(Box.createVerticalStrut(10));

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
        dicePanel.setMaximumSize(new Dimension(220, 110));

        JLabel diceTitle = new JLabel("Dice");
        diceTitle.setFont(new Font("Serif", Font.BOLD, 12));
        diceTitle.setForeground(new Color(80, 50, 30));
        diceTitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        diceResultLabel = new JLabel("?");
        diceResultLabel.setFont(new Font("Serif", Font.BOLD, 48));
        diceResultLabel.setForeground(new Color(80, 120, 80));
        diceResultLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        dicePanel.add(diceTitle);
        dicePanel.add(Box.createVerticalStrut(6));
        dicePanel.add(diceResultLabel);

        left.add(dicePanel);

        panel.add(left, BorderLayout.WEST);

        JPanel center = new JPanel(new BorderLayout());
        center.setOpaque(false);

        currentPlayerLabel = new JLabel("Waiting for players...");
        currentPlayerLabel.setOpaque(true);
        currentPlayerLabel.setBackground(new Color(255, 250, 240));
        currentPlayerLabel.setBorder(new LineBorder(new Color(210, 180, 150), 1, true));
        currentPlayerLabel.setFont(new Font("Serif", Font.BOLD, 16));
        currentPlayerLabel.setForeground(new Color(80, 60, 40));
        currentPlayerLabel.setHorizontalAlignment(SwingConstants.CENTER);
        currentPlayerLabel.setPreferredSize(new Dimension(200, 36));
        center.add(currentPlayerLabel, BorderLayout.NORTH);

        playersInfoPanel = new JPanel();
        playersInfoPanel.setOpaque(false);
        playersInfoPanel.setLayout(new BoxLayout(playersInfoPanel, BoxLayout.Y_AXIS));
        JScrollPane playersScroll = new JScrollPane(playersInfoPanel);
        playersScroll.setBorder(new LineBorder(new Color(220, 200, 180), 1, true));
        playersScroll.setPreferredSize(new Dimension(420, 160));
        playersScroll.getViewport().setBackground(new Color(250,246,238));
        center.add(playersScroll, BorderLayout.CENTER);

        panel.add(center, BorderLayout.CENTER);

        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));

        JLabel logTitle = new JLabel("Game Log");
        logTitle.setFont(new Font("Serif", Font.BOLD, 12));
        logTitle.setForeground(new Color(90, 70, 50));
        logTitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        gameLogArea = new JTextArea();
        gameLogArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        gameLogArea.setEditable(false);
        gameLogArea.setLineWrap(true);
        gameLogArea.setWrapStyleWord(true);
        gameLogArea.setBackground(new Color(255, 255, 250));
        gameLogArea.setForeground(new Color(40, 30, 20));
        gameLogArea.setBorder(new EmptyBorder(8, 8, 8, 8));

        JScrollPane logScroll = new JScrollPane(gameLogArea);
        logScroll.setPreferredSize(new Dimension(420, 160));
        logScroll.setBorder(new LineBorder(new Color(220, 200, 180), 2, true));
        logScroll.getViewport().setBackground(new Color(255,255,250));

        right.add(logTitle);
        right.add(Box.createVerticalStrut(8));
        right.add(logScroll);

        panel.add(right, BorderLayout.EAST);

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

    /**
     * updatePlayersInfoPanel:
     * - While gameStarted == true: show current players at top, then all history.
     * - When gameStarted == false: show full leaderboard sorted.
     */
    private void updatePlayersInfoPanel() {
        playersInfoPanel.removeAll();

        class RowBuilder {
            JPanel build(Player p) {
                JPanel card = new JPanel(new BorderLayout(8, 0));
                card.setMaximumSize(new Dimension(420, 80));
                card.setBackground(new Color(255, 255, 250));
                card.setBorder(BorderFactory.createCompoundBorder(new LineBorder(new Color(220,200,180),1,true), new EmptyBorder(6,8,6,8)));

                JPanel avatarBox = new JPanel() {
                    @Override
                    protected void paintComponent(Graphics g) {
                        super.paintComponent(g);
                        Graphics2D g2 = (Graphics2D) g;
                        if (p.getAvatar() != null) {
                            BufferedImage img = p.getAvatar();
                            int w = getWidth(), h = getHeight();
                            int iw = img.getWidth(), ih = img.getHeight();
                            double scale = Math.min((w-8)/(double)iw, (h-8)/(double)ih);
                            int dw = (int)(iw*scale), dh = (int)(ih*scale);
                            g2.drawImage(img, 4 + (w-8-dw)/2, 4 + (h-8-dh)/2, dw, dh, null);
                        } else {
                            g2.setColor(p.getColor());
                            g2.fillOval(4,4,44,44);
                            g2.setColor(p.getColor().darker());
                            g2.setStroke(new BasicStroke(2));
                            g2.drawOval(4,4,44,44);
                            g2.setColor(new Color(20,18,12));
                            g2.setFont(new Font("Serif", Font.BOLD, 18));
                            FontMetrics fm = g2.getFontMetrics();
                            String in = p.getName().substring(0,1).toUpperCase();
                            g2.drawString(in, 4 + (44 - fm.stringWidth(in))/2, 4 + (44 + fm.getAscent())/2 - 2);
                        }
                    }
                };
                avatarBox.setPreferredSize(new Dimension(60, 56));
                avatarBox.setOpaque(false);

                String history = getScoreSummary(p.getName());
                JLabel info = new JLabel("<html><b>" + p.getName() + "</b> • Node " + p.getPosition() + " &nbsp; ★ " + p.getStars()
                        + " • Pts: " + p.getScore()
                        + (p.isFinished() ? " • Finished" : "")
                        + "<br/><span style='font-size:11px;color:#6b4f36;'>History: " + history + "</span></html>");
                info.setFont(new Font("Serif", Font.PLAIN, 13));
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
                JPanel card = new JPanel(new BorderLayout(8, 0));
                card.setMaximumSize(new Dimension(420, 48));
                card.setBackground(new Color(255, 255, 250));
                card.setBorder(BorderFactory.createCompoundBorder(new LineBorder(new Color(220,200,180),1,true), new EmptyBorder(6,8,6,8)));
                JLabel info = new JLabel("<html><b>" + name + "</b> &nbsp; <span style='font-size:11px;color:#6b4f36;'>History: "
                        + getScoreSummary(name) + "</span></html>");
                info.setFont(new Font("Serif", Font.PLAIN, 13));
                info.setForeground(new Color(70, 50, 30));
                card.add(info, BorderLayout.CENTER);
                playersInfoPanel.add(card);
                playersInfoPanel.add(Box.createVerticalStrut(6));
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
            JPanel card = new JPanel(new BorderLayout(8, 0));
            card.setMaximumSize(new Dimension(420, 54));
            card.setBackground(new Color(255, 255, 250));
            card.setBorder(BorderFactory.createCompoundBorder(new LineBorder(new Color(220,200,180),1,true), new EmptyBorder(6,8,6,8)));

            JLabel info = new JLabel("<html><b>" + name + "</b> &nbsp; <span style='font-size:12px;color:#6b4f36;'>"
                    + "Wins: " + rec.wins + " • Games: " + rec.gamesPlayed + " • Stars: " + rec.totalStars + " • Pts: " + rec.totalScore + "</span></html>");
            info.setFont(new Font("Serif", Font.PLAIN, 13));
            info.setForeground(new Color(70, 50, 30));

            card.add(info, BorderLayout.CENTER);
            playersInfoPanel.add(card);
            playersInfoPanel.add(Box.createVerticalStrut(6));
        }

        playersInfoPanel.revalidate();
        playersInfoPanel.repaint();
    }

    // ---------- UI helpers ----------
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

    // ---------- Game logic ----------
    private void startGame() {
        String numPlayersStr = JOptionPane.showInputDialog(this,
                "How many players? (2-6)",
                "Number of Players",
                JOptionPane.QUESTION_MESSAGE);
        if (numPlayersStr == null) return;
        try {
            int numPlayers = Integer.parseInt(numPlayersStr);
            if (numPlayers < 2 || numPlayers > 6) {
                JOptionPane.showMessageDialog(this, "Please enter 2-6 players.", "Invalid", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // reset stars claimed for this match
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
                if (name == null) { return; } // cancelled
                if (name.trim().isEmpty()) name = "Player " + (i + 1);
                name = name.trim();

                // avatar chooser
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

            // generate ladders for this match
            generateRandomLinks();

            // generate tile points
            for (int i = 1; i <= BOARD_CELLS; i++) {
                // Start tile maybe 0 points; keep others 1..10
                tilePoints[i] = (i == 1) ? 0 : (1 + random.nextInt(10));
            }

            // reset finished flag & scores/stars for players
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

            // START background music loop (if available)
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

    // Sound helper for short sounds (one-shot)
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
                        addLog("[Sound] File not found: " + filename);
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
            } catch (UnsupportedAudioFileException uex) {
                System.err.println("[Sound] Unsupported audio file: " + filename + " -> " + uex.getMessage());
                addLog("[Sound] Unsupported audio: " + filename);
            } catch (LineUnavailableException lex) {
                System.err.println("[Sound] Line unavailable for: " + filename + " -> " + lex.getMessage());
                addLog("[Sound] Audio line unavailable: " + filename);
            } catch (IOException ioex) {
                System.err.println("[Sound] IO error reading: " + filename + " -> " + ioex.getMessage());
                addLog("[Sound] IO error: " + filename);
            } catch (Exception ex) {
                System.err.println("[Sound] Error playing sound: " + filename + " -> " + ex.getMessage());
                addLog("[Sound] Error: " + filename);
            } finally {
                try { if (audioIn != null) audioIn.close(); } catch (IOException ignored) {}
            }
        }).start();
    }

    /**
     * Play background loop using Clip.loop(Clip.LOOP_CONTINUOUSLY).
     * Will try classpath resource first, then working-directory file.
     * If already playing, does nothing.
     */
    private void playBackgroundLoop(String filename) {
        if (backgroundClip != null && backgroundClip.isOpen()) {
            addLog("[Music] Background loop already playing.");
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
                        addLog("[Music] Backsound file not found: " + filename);
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

                // optional: reduce background volume a bit if control exists
                try {
                    FloatControl vol = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                    float dB = -10.0f; // lower by 10dB
                    vol.setValue(Math.max(vol.getMinimum(), Math.min(vol.getMaximum(), dB)));
                } catch (Exception ignore) {}

                backgroundClip = clip;
                runningClips.add(clip);

                clip.loop(Clip.LOOP_CONTINUOUSLY);
                clip.start();

                addLog("[Music] Backsound loop started: " + filename);
            } catch (UnsupportedAudioFileException uex) {
                System.err.println("[Music] Unsupported audio file: " + filename + " -> " + uex.getMessage());
                addLog("[Music] Unsupported audio: " + filename);
            } catch (LineUnavailableException lex) {
                System.err.println("[Music] Line unavailable for: " + filename + " -> " + lex.getMessage());
                addLog("[Music] Audio line unavailable: " + filename);
            } catch (IOException ioex) {
                System.err.println("[Music] IO error reading: " + filename + " -> " + ioex.getMessage());
                addLog("[Music] IO error: " + filename);
            } catch (Exception ex) {
                System.err.println("[Music] Error playing background sound: " + filename + " -> " + ex.getMessage());
                addLog("[Music] Error: " + filename);
            } finally {
                try { if (audioIn != null) audioIn.close(); } catch (IOException ignored) {}
            }
        }).start();
    }

    /**
     * Stop background loop (if playing).
     */
    private void stopBackgroundLoop() {
        if (backgroundClip != null) {
            try {
                backgroundClip.stop();
                backgroundClip.close();
            } catch (Exception ignored) {}
            runningClips.remove(backgroundClip);
            backgroundClip = null;
            addLog("[Music] Backsound stopped.");
        }
    }

    private void rollDiceWithAnimation() {
        if (!gameStarted || currentPlayer == null || isAnimating) return;

        rollDiceButton.setEnabled(false);
        isAnimating = true;

        playSound("crash-spin.wav");

        int finalDiceValue = random.nextInt(6) + 1;
        double probability = random.nextDouble();
        boolean isForward = probability < 0.75; // mostly forward

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

    private void awardTilePoints(Player p, int pos) {
        if (pos < 1 || pos > BOARD_CELLS) return;
        int pts = tilePoints[pos];
        if (pts == 0) return;
        p.addScore(pts);
        addLog("│ ➕ " + p.getName() + " received " + pts + " pts for landing on Node " + pos + " (tile points).");
        updatePlayersInfoPanel();
    }

    /**
     * animateMovementWithAutoLadder:
     * - Moves player step-by-step.
     * - If usePrimePower == true and moving forward, player can auto-use ladder when passing/landing on ladder-from.
     * - Boss encounters are handled ONLY on landing (not during passing).
     */
    private void animateMovementWithAutoLadder(int startPos, int moves, boolean isForward, boolean usePrimePower) {
        final int[] currentStep = {0};
        final int[] remaining = {moves};
        final int[] currentPos = {startPos};
        final List<Integer> pathTaken = new ArrayList<>();
        pathTaken.add(startPos);
        final boolean[] extraPending = {false};

        javax.swing.Timer t = new javax.swing.Timer(420, null);
        t.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (remaining[0] <= 0) { t.stop(); // proceed landing effects (boss / tile / finish)
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

                pathTaken.add(next);
                gameBoard.setHighlightPath(new ArrayList<>(pathTaken));
                gameBoard.repaint();
                updatePlayersInfoPanel();

                addLog("│ Step " + currentStep[0] + ": Node " + next + " (left: " + remaining[0] + ")");

                // PRIME auto-ladder while moving: only active if player started the turn on a prime and moving forward
                if (usePrimePower && isForward && remaining[0] > 0) {
                    for (RandomLink link : randomLinks) {
                        if (link.isLadder() && link.getFrom() == currentPos[0]) {
                            final RandomLink capturedLink = link; // <-- capture for lambda
                            addLog("│ ✦ PRIME: Auto-using LADDER!");
                            addLog("│ Teleporting: " + capturedLink.getFrom() + " → " + capturedLink.getTo());
                            playSound("move.wav");
                            t.stop();
                            javax.swing.Timer teleport = new javax.swing.Timer(700, evt -> {
                                currentPos[0] = capturedLink.getTo();
                                currentPlayer.setPosition(capturedLink.getTo());
                                pathTaken.add(capturedLink.getTo());
                                gameBoard.setTeleportEffect(capturedLink);
                                gameBoard.setHighlightPath(new ArrayList<>(pathTaken));
                                gameBoard.repaint();
                                updatePlayersInfoPanel();
                                if (remaining[0] == 0) {
                                    boolean awarded = awardStarIfAvailable(currentPlayer, currentPos[0]);
                                    if (awarded) extraPending[0] = true;
                                }
                                // award tile points for teleport destination (tile points persist)
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
                    // landing will be handled at top of loop when remaining <= 0 on next tick, but we also support immediate landing here
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

    /**
     * Called when movement finished (after any teleport effects).
     * Handles:
     * - awarding star if on multiple of 5,
     * - awarding tile points,
     * - boss encounter if node is a boss (only on landing),
     * - finishing turn / finishing match logic.
     */
    private void handleLandingAfterMove(int landedPos, boolean extraPending) {
        addLog("│ Landed: Node " + landedPos);
        // award star
        boolean awarded = awardStarIfAvailable(currentPlayer, landedPos);
        if (awarded) extraPending = true;

        // award tile points (tile points persist)
        awardTilePoints(currentPlayer, landedPos);

        gameBoard.setHighlightPath(null);
        gameBoard.setTeleportEffect(null);
        gameBoard.repaint();

        // Boss encounter check - only when landed exactly on boss node
        if (bossNodes.contains(landedPos)) {
            addLog("│ 👾 Boss is present at Node " + landedPos + " — triggering encounter.");

            final int _capLanded = landedPos;
            final boolean _capExtra = extraPending;
            final Player _capPlayer = currentPlayer;

            triggerBossEncounter(_capLanded, _capPlayer, success -> {

                if (success) {
                    // WIN → continue as normal
                    finishTurnAfterLanding(_capLanded, _capExtra);
                } else {
                    // FAIL → cannot continue; return to previous tile
                    int prev = Math.max(1, _capLanded - 1);
                    _capPlayer.setPosition(prev);

                    addLog("│ ❌ " + _capPlayer.getName() +
                            " failed the boss and is returned to Node " + prev + ". Turn ends.");

                    gameBoard.repaint();
                    updatePlayersInfoPanel();

                    // proceed to next non-finished player
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

    /**
     * Finish-turn flow after landing and any boss encounter completed.
     * If player reached finish, mark as finished.
     * End-match early condition: if only one player remains NOT finished, match ends immediately.
     */
    private void finishTurnAfterLanding(int finalPosition, boolean extraTurn) {
        addLog("│ Final: Node " + finalPosition);
        gameBoard.setHighlightPath(null);
        gameBoard.setTeleportEffect(null);
        gameBoard.repaint();

        if (finalPosition == BOARD_CELLS) {
            // player finished -- mark finished and do not requeue
            currentPlayer.setFinished(true);
            addLog("│ 🎉 " + currentPlayer.getName() + " reached FINISH!");

            // Count how many players are still not finished
            int notFinished = 0;
            for (Player p : players) if (!p.isFinished()) notFinished++;

            // If only 0 remain -> all finished (normal)
            // If only 1 remain -> end match early per requirement
            if (notFinished <= 1) {
                addLog("│ Ending match early — only " + notFinished + " player(s) still not finished.");
                // Determine winner by points + stars*STAR_TO_POINT
                Player winner = computeWinnerByPointsAndStars();
                addLog("│ Winner: " + (winner != null ? winner.getName() : "NONE"));
                // update persistent scores
                updateScoresAfterMatch(winner);

                // STOP background music before celebratory sound
                stopBackgroundLoop();

                // show summary and finish
                playSound("confetti.wav");
                StringBuilder sb = new StringBuilder();
                sb.append("Match ended!\n\nFinal summary (points + stars*").append(STAR_TO_POINT).append("):\n");
                for (Player p : players) {
                    int total = p.getScore() + p.getStars() * STAR_TO_POINT;
                    sb.append(String.format(" • %s — Points: %d • Stars: %d • Total: %d\n", p.getName(), p.getScore(), p.getStars(), total));
                }
                if (winner != null) sb.append("\nWinner: ").append(winner.getName()).append("\n");
                JOptionPane.showMessageDialog(this, sb.toString(), "Match Result", JOptionPane.INFORMATION_MESSAGE);

                // reset for next game state
                gameStarted = false;
                startButton.setEnabled(true);
                rollDiceButton.setEnabled(false);
                isAnimating = false;
                updatePlayersInfoPanel();
                addLog("└─────────────────────");
                return;
            } else {
                // Not early-end — proceed as usual (wait other players)
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

        if (extraTurn) {
            addLog("│ ➜ Extra turn for " + currentPlayer.getName() + " (keeps turn)");
            addLog("└─────────────────────");
            currentPlayerLabel.setText("Turn: " + currentPlayer.getName());
            isAnimating = false;
            rollDiceButton.setEnabled(true);
            updatePlayersInfoPanel();
            return;
        }

        // normal rotation: put player to back of queue only if not finished
        addLog("└─────────────────────");
        playerQueue.add(currentPlayer);
        // find next player who is not finished
        Player next = pollNextActivePlayer();
        currentPlayer = next;
        if (currentPlayer != null) currentPlayerLabel.setText("Turn: " + currentPlayer.getName());
        addLog("Next: " + (currentPlayer != null ? currentPlayer.getName() : "—"));
        addLog("");
        isAnimating = false;
        rollDiceButton.setEnabled(currentPlayer != null);
        updatePlayersInfoPanel();
    }

    // helper: return next player who is not finished, poll from queue and requeue if still active
    private Player pollNextActivePlayer() {
        // rotate queue until find an active player or queue exhausted
        int attempts = playerQueue.size();
        while (attempts-- > 0) {
            Player p = playerQueue.poll();
            if (p == null) break;
            if (!p.isFinished()) {
                // found active
                return p;
            } else {
                // skip finished player, don't re-add
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
                // tie-breaker: more stars wins
                if (best != null && p.getStars() > best.getStars()) best = p;
            }
        }
        return best;
    }

    private void triggerBossEncounter(int node, Player player, java.util.function.Consumer<Boolean> callback) {
        addLog("│ 👾 Boss encountered at Node " + node + " — " + player.getName());

        int a = random.nextInt(12) + 1;
        int b = random.nextInt(12) + 1;
        String[] ops = {"+", "-", "*"};
        String op = ops[random.nextInt(ops.length)];
        int correct;
        switch (op) {
            case "+": correct = a + b; break;
            case "-": correct = a - b; break;
            default:  correct = a * b; break;
        }

        String q = String.format("Solve to defeat the Boss: %d %s %d = ?", a, op, b);
        String answer = JOptionPane.showInputDialog(this, q, "Boss Encounter", JOptionPane.QUESTION_MESSAGE);
        boolean success = false;
        if (answer != null) {
            try {
                int given = Integer.parseInt(answer.trim());
                success = (given == correct);
            } catch (NumberFormatException ex) {
                success = false;
            }
        }

        if (success) {
            addLog("│ ✅ " + player.getName() + " defeated the boss! +" + bossWinPoints + " pts, +" + bossWinStars + " stars");
            player.addScore(bossWinPoints);
            for (int i = 0; i < bossWinStars; i++) player.addStar();

            updatePlayersInfoPanel();
            JOptionPane.showMessageDialog(this, "You defeated the boss! You earn +" + bossWinPoints + " points and +" + bossWinStars + " stars.", "Victory", JOptionPane.INFORMATION_MESSAGE);
            callback.accept(true);
        } else {
            addLog("│ ❌ " + player.getName() + " failed the boss challenge. " + bossLosePoints + " pts, " + bossLoseStars + " stars");
            player.addScore(bossLosePoints);
            player.addStar(bossLoseStars);

            updatePlayersInfoPanel();
            JOptionPane.showMessageDialog(this, "You lost to the boss. Penalty: " + bossLosePoints + " points, " + bossLoseStars + " star(s).", "Defeat", JOptionPane.WARNING_MESSAGE);
            callback.accept(false);
        }
    }

    private void addLog(String message) {
        gameLogArea.append(message + "\n");
        gameLogArea.setCaretPosition(gameLogArea.getDocument().getLength());
    }

    // ---------- Ladders generation ----------
    /**
     * Generate non-overlapping ladders with additional rule:
     * - no shared start/end points,
     * - no identical pairs,
     * - no crossing segments (if from1 < from2 < to1 < to2 it's crossing),
     * - no horizontal ladders (start and end must be in different board rows).
     */
    private void generateRandomLinks() {
        randomLinks.clear();
        Set<Integer> usedEndpoints = new HashSet<>(); // prevent same start/end reuse
        int attempts = 0;
        final int TARGET = 5;     // desired number of ladders
        final int MAX_ATTEMPTS = 2000;

        // helper to compute row index (0..7) for a board position 1..64
        final int BOARD_SIZE = 8;
        java.util.function.IntUnaryOperator rowOf = pos -> {
            int nodeNumber = BOARD_CELLS - pos + 1; // BOARD_CELLS is 64 constant
            return (nodeNumber - 1) / BOARD_SIZE;
        };

        while (randomLinks.size() < TARGET && attempts < MAX_ATTEMPTS) {
            attempts++;
            int a = random.nextInt(54) + 6; // 6..59
            int b = random.nextInt(54) + 6;
            if (a == b) continue;
            int from = Math.min(a, b);
            int to   = Math.max(a, b);

            // minimal distance constraint
            if (to - from < 3) continue;

            // don't reuse endpoints
            if (usedEndpoints.contains(from) || usedEndpoints.contains(to)) continue;

            // prevent horizontal ladders: row(from) must not equal row(to)
            int rf = rowOf.applyAsInt(from);
            int rt = rowOf.applyAsInt(to);
            if (rf == rt) continue; // same row -> considered "horizontal" => skip

            // check against existing links: identical pair or crossing
            boolean bad = false;
            for (RandomLink e : randomLinks) {
                int ef = e.getFrom();
                int et = e.getTo();
                // identical pair (shouldn't happen but check)
                if (ef == from && et == to) { bad = true; break; }
                // prevent crossing: (from < ef < to < et) or (ef < from < et < to)
                if ((from < ef && ef < to && to < et) || (ef < from && from < et && et < to)) {
                    bad = true;
                    break;
                }
                // also prevent endpoints touching existing ones (already checked via usedEndpoints),
                // but keep here for double-safety
                if (ef == from || ef == to || et == from || et == to) { bad = true; break; }
            }
            if (bad) continue;

            // passed all checks — accept this ladder
            randomLinks.add(new RandomLink(from, to, true));
            usedEndpoints.add(from);
            usedEndpoints.add(to);
        }

        if (randomLinks.size() < TARGET) {
            addLog("[Ladders] Could only place " + randomLinks.size() + " non-overlapping, non-horizontal ladders (attempts: " + attempts + ").");
        } else {
            addLog("[Ladders] Placed ladders: " + randomLinksSummary());
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

    // ---------- Inner helper classes ----------
    static class Player implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String name;
        private int position;
        private Color color;
        private int stars;
        private int score;
        private transient BufferedImage avatar;
        private boolean finished = false;

        public Player(String name, Color color) { this.name = name; this.position = 1; this.color = color; this.stars = 0; this.score = 0; this.avatar = null; this.finished=false; }
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
    }

    static class RandomLink implements Serializable {
        private static final long serialVersionUID = 1L;
        private int from, to;
        private boolean isLadder;
        public RandomLink(int from, int to, boolean isLadder) { this.from = from; this.to = to; this.isLadder = isLadder; }
        public int getFrom() { return from; }
        public int getTo() { return to; }
        public boolean isLadder() { return isLadder; }
    }

    class GameBoard extends JPanel {
        private static final int BOARD_SIZE = 8;
        private static final int CELL_SIZE = 92;
        private static final int PADDING = 30;
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
            setPreferredSize(new Dimension(BOARD_SIZE * CELL_SIZE + PADDING * 2 + 60, BOARD_SIZE * CELL_SIZE + PADDING * 2 + 60));
            setBackground(new Color(255, 253, 249));

            animationTimer = new Timer(45, e -> {
                glowPhase += 0.03f;
                bobPhase += 0.08f;
                repaint();
            });
            animationTimer.setRepeats(true);
            animationTimer.start();
        }

        public void setPlayers(List<Player> players) { this.players = players; repaint(); }
        public void setHighlightPath(List<Integer> path) { this.highlightPath = (path != null) ? new ArrayList<>(path) : new ArrayList<>(); repaint(); }
        public void setTeleportEffect(RandomLink effect) { this.teleportEffect = effect; repaint(); }
        public void setRandomLinks(List<RandomLink> links) { this.boardLinks = (links != null) ? links : new ArrayList<>(); repaint(); }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            GradientPaint bg = new GradientPaint(0, 0, new Color(255, 253, 248), getWidth(), getHeight(), new Color(245, 240, 230));
            g2.setPaint(bg);
            g2.fillRect(0, 0, getWidth(), getHeight());

            int node = BOARD_CELLS;
            for (int row = 0; row < BOARD_SIZE; row++) {
                for (int col = 0; col < BOARD_SIZE; col++) {
                    int x = PADDING + ((row % 2 == 0) ? (BOARD_SIZE - 1 - col) * CELL_SIZE : col * CELL_SIZE);
                    int y = PADDING + row * CELL_SIZE;

                    // prime tiles colored dark brown background
                    if (isPrime(node)) {
                        g2.setColor(new Color(120, 72, 42)); // dark brown
                    } else {
                        if ((row + col) % 2 == 0) g2.setColor(new Color(252, 245, 235));
                        else g2.setColor(new Color(249, 240, 226));
                    }

                    if (highlightPath != null && highlightPath.contains(node)) {
                        float scale = 1f + 0.04f * (float)Math.sin(bobPhase + node * 0.3);
                        int w = (int)((CELL_SIZE - 8) * scale);
                        int h = (int)((CELL_SIZE - 8) * scale);
                        int dx = (CELL_SIZE - w) / 2;
                        int dy = (CELL_SIZE - h) / 2;
                        g2.fillRoundRect(x + 4 + dx, y + 4 + dy, w, h, 12, 12);
                    } else {
                        g2.fillRoundRect(x + 4, y + 4, CELL_SIZE - 8, CELL_SIZE - 8, 12, 12);
                    }

                    g2.setColor(new Color(220, 200, 180));
                    g2.setStroke(new BasicStroke(2));
                    g2.drawRoundRect(x + 4, y + 4, CELL_SIZE - 8, CELL_SIZE - 8, 12, 12);

                    g2.setFont(new Font("Serif", Font.BOLD, 16));
                    // for prime we want text still visible: choose light text for dark brown
                    if (isPrime(node)) g2.setColor(new Color(245,230,210));
                    else g2.setColor(new Color(95, 70, 50));
                    String txt = String.valueOf(node);
                    g2.drawString(txt, x + 12, y + 20);

                    // draw tile points small
                    int pts = tilePoints[node];
                    if (pts > 0) {
                        g2.setFont(new Font("Dialog", Font.PLAIN, 12));
                        g2.setColor(isPrime(node) ? new Color(240, 220, 180) : new Color(90, 65, 40));
                        g2.drawString("+" + pts, x + 10, y + CELL_SIZE - 12);
                    }

                    if (node % 5 == 0) {
                        g2.setFont(new Font("Dialog", Font.PLAIN, 18));
                        if (starsClaimed[node]) g2.setColor(new Color(200, 200, 200));
                        else g2.setColor(new Color(235, 180, 70));
                        g2.drawString("★", x + CELL_SIZE - 34, y + 28);
                    }

                    if (boardLinks != null) {
                        for (RandomLink link : boardLinks) {
                            if (link.getFrom() == node) {
                                // draw ladder indicator near 'from'
                                g2.setFont(new Font("Dialog", Font.PLAIN, 18));
                                g2.setColor(new Color(101, 67, 33));
                                g2.drawString("↗", x + CELL_SIZE - 46, y + CELL_SIZE - 12);
                            }
                        }
                    }

                    if (bossNodes.contains(node)) {
                        g2.setFont(new Font("Dialog", Font.PLAIN, 20));
                        g2.setColor(new Color(180, 60, 80));
                        g2.drawString("👾", x + CELL_SIZE - 46, y + CELL_SIZE - 30);
                    }

                    if (node == 1) {
                        g2.setColor(new Color(200, 230, 200));
                        g2.fillRoundRect(x + 16, y + CELL_SIZE - 46, CELL_SIZE - 36, 28, 10, 10);
                        g2.setColor(new Color(80, 70, 50));
                        g2.setFont(new Font("Serif", Font.BOLD, 12));
                        g2.drawString("START", x + 28, y + CELL_SIZE - 26);
                    } else if (node == BOARD_CELLS) {
                        g2.setColor(new Color(255, 220, 200));
                        g2.fillRoundRect(x + 16, y + CELL_SIZE - 46, CELL_SIZE - 36, 28, 10, 10);
                        g2.setColor(new Color(85, 40, 40));
                        g2.setFont(new Font("Serif", Font.BOLD, 12));
                        g2.drawString("FINISH", x + 28, y + CELL_SIZE - 26);
                    }

                    node--;
                }
            }

            // draw ladders as lines between centers (decorative)
            if (boardLinks != null) {
                for (RandomLink link : boardLinks) {
                    Point from = getCoordinatesForPosition(link.getFrom());
                    Point to = getCoordinatesForPosition(link.getTo());
                    if (from == null || to == null) continue;
                    int fx = from.x + CELL_SIZE / 2;
                    int fy = from.y + CELL_SIZE / 2;
                    int tx = to.x + CELL_SIZE / 2;
                    int ty = to.y + CELL_SIZE / 2;

                    g2.setStroke(new BasicStroke(6, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.setColor(new Color(101, 67, 33, 200));
                    g2.drawLine(fx, fy, tx, ty);

                    if (teleportEffect != null && teleportEffect == link) {
                        float pulse = 0.55f + 0.45f * (float)Math.sin(glowPhase * 2.0);
                        int alpha = Math.min(220, (int)(220 * pulse));
                        g2.setStroke(new BasicStroke(10, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g2.setColor(new Color(255, 200, 120, alpha));
                        g2.drawLine(fx, fy, tx, ty);
                    }
                }
            }

            if (players != null) {
                for (int i = 0; i < players.size(); i++) {
                    Player p = players.get(i);
                    int pos = p.getPosition();
                    Point coords = getCoordinatesForPosition(pos);
                    if (coords == null) continue;

                    int tokenSize = 36;
                    int offX = (i % 3) * 20 - 20;
                    int offY = (i / 3) * 12 + 18;
                    int bob = (int)(6 * Math.sin(bobPhase + i * 0.8));

                    int px = coords.x + CELL_SIZE / 2 - tokenSize / 2 + offX;
                    int py = coords.y + CELL_SIZE / 2 - tokenSize / 2 + offY - bob;

                    g2.setColor(new Color(0, 0, 0, 40));
                    g2.fillOval(px + 6, py + 10, tokenSize, tokenSize / 2);

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
                        GradientPaint gp = new GradientPaint(px, py, p.getColor().brighter(), px, py + tokenSize, p.getColor().darker());
                        g2.setPaint(gp);
                        g2.fillOval(px, py, tokenSize, tokenSize);
                        g2.setColor(new Color(110, 80, 60));
                        g2.setStroke(new BasicStroke(2));
                        g2.drawOval(px, py, tokenSize, tokenSize);

                        g2.setColor(new Color(20, 18, 12));
                        g2.setFont(new Font("Serif", Font.BOLD, 14));
                        String in = p.getName().substring(0, 1).toUpperCase();
                        FontMetrics fm = g2.getFontMetrics();
                        g2.drawString(in, px + (tokenSize - fm.stringWidth(in)) / 2, py + (tokenSize + fm.getAscent()) / 2 - 2);
                    }
                }
            }
        }

        private Point getCoordinatesForPosition(int position) {
            if (position < 1 || position > BOARD_CELLS) return null;
            int nodeNumber = BOARD_CELLS - position + 1;
            int row = (nodeNumber - 1) / BOARD_SIZE;
            int col = (nodeNumber - 1) % BOARD_SIZE;
            int x = PADDING;
            int y = PADDING + row * CELL_SIZE;
            if (row % 2 == 0) x = PADDING + (BOARD_SIZE - 1 - col) * CELL_SIZE;
            else x = PADDING + col * CELL_SIZE;
            return new Point(x, y);
        }
    }

    // ---------- Main ----------
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            AdventureGame g = new AdventureGame();
            g.setVisible(true);
        });
    }
}
