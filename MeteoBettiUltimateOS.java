import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.plaf.basic.ComboPopup;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import javax.net.ssl.HttpsURLConnection;

/**
 * METEOBETTI ULTIMATE OS - Dashboard meteo desktop con interfaccia in vetro.
 */
public class MeteoBettiUltimateOS {

    private static final int RADAR_MAP_SIZE = 512;
    private static final int RADAR_RADIUS_KM = 100;
    private static final double EARTH_CIRCUMFERENCE_METERS = 40_075_016.686;
    private static final String MET_NO_USER_AGENT = "MeteoBetti/1.0 (desktop weather application)";
    private static final long MET_NO_CACHE_MILLIS = TimeUnit.MINUTES.toMillis(30);
    private static final int MAX_HTTP_RESPONSE_CHARS = 4 * 1024 * 1024;
    private static final int MAX_TILE_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_TILE_DIMENSION = 1024;
    private static final String DEFAULT_USER_NAME = "Utente";
    private static final String USER_NAME_PREFERENCE_KEY = "displayName";
    private static final Set<String> ALLOWED_REMOTE_HOSTS = Set.of(
            "api.open-meteo.com",
            "air-quality-api.open-meteo.com",
            "geocoding-api.open-meteo.com",
            "api.met.no",
            "api.rainviewer.com",
            "tile.openstreetmap.org");
    private static final ThreadPoolExecutor RADAR_MAP_EXECUTOR =
            new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    private static final ThreadPoolExecutor RADAR_TILE_EXECUTOR =
            new ThreadPoolExecutor(6, 6, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    private static final Map<String, BufferedImage> RADAR_TILE_CACHE =
            new LinkedHashMap<String, BufferedImage>(96, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                    return size() > 80;
                }
            };
    private static final Map<String, CachedWeatherForecast> MET_NO_FORECAST_CACHE =
            new LinkedHashMap<String, CachedWeatherForecast>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedWeatherForecast> eldest) {
                    return size() > 32;
                }
            };

    protected static JFrame rootWindow;
    protected static JPanel mainCardContainer;
    protected static CardLayout pageCardLayout;
    protected static Image currentWallpaperImage = null;

    protected static JLabel headerLocationLabel;
    protected static JLabel mainLocationDisplayLabel;
    protected static JLabel mainTempDisplayLabel;
    protected static JLabel conditionTextLabel;
    protected static DefaultTableModel forecastTableModel;
    protected static HourlyTimelineStrip hourlyTimelineStrip;
    protected static JTextField searchTextField;
    protected static String customUserName = DEFAULT_USER_NAME;
    protected static AppNavBar appNavBar;
    protected static JLabel heroIconLabel;
    protected static String uiFontFamily;
    protected static DailyForecastPanel dailyForecastPanel;
    protected static DailyDetailPanel dailyDetailPanel;

    protected static TelemetryGaugeBox humidityMetricBox;
    protected static TelemetryGaugeBox uvMetricBox;
    protected static TelemetryGaugeBox pressureMetricBox;
    protected static TelemetryGaugeBox airQualityMetricBox;
    protected static AnalyticsForecastPanel analyticsForecastPanel;
    protected static RadarMapPanel radarMapPanel;
    protected static JLabel airQualitySummaryLabel;
    protected static JLabel airQualityAqiLabel;
    protected static JLabel airQualityPm25Label;
    protected static JLabel airQualityPm10Label;
    protected static JLabel airQualityNo2Label;
    protected static JLabel airQualityTrendsTitleLabel;
    protected static AirQualityTrendsPanel airQualityTrendsPanel;
    protected static JComboBox<ForecastDateOption> airQualityDateSelector;
    protected static JComboBox<ForecastDateOption> radarDateSelector;
    private static boolean updatingForecastDateSelectors;

    protected static JPanel discreetDropdownPanel;
    protected static boolean isMenuVisible = false;

    protected static String stationCity = "Rilevamento in corso...";
    protected static String stationCountry = "Italia";
    protected static double stationLat = 0.0;
    protected static double stationLon = 0.0;
    protected static double stationTemp = 0.0;
    protected static double stationApparentTemp = 0.0;
    protected static double stationHumidity = 0.0;
    protected static double stationPressure = 0.0;
    protected static double stationWindSpeed = 0.0;
    protected static double stationUvIndex = 0.0;
    protected static double stationEuropeanAqi = Double.NaN;
    protected static double stationPm25 = Double.NaN;
    protected static double stationPm10 = Double.NaN;
    protected static double stationNo2 = Double.NaN;
    protected static double stationOzone = Double.NaN;
    private static volatile String weatherDataSourceName = "Open-Meteo";

    public static void main(String[] args) {
        customUserName = loadUserName();
        loadAppWallpaper("☀️");
        Color sheet = new Color(22, 25, 30);
        UIManager.put("OptionPane.background", sheet);
        UIManager.put("Panel.background", sheet);
        UIManager.put("OptionPane.foreground", new Color(239, 243, 248));
        UIManager.put("OptionPane.messageForeground", new Color(244, 247, 250));
        UIManager.put("OptionPane.buttonBackground", new Color(39, 43, 50));
        UIManager.put("OptionPane.buttonForeground", new Color(245, 247, 250));
        UIManager.put("OptionPane.messageFont", new Font("SansSerif", Font.PLAIN, 13));
        UIManager.put("OptionPane.buttonFont", new Font("SansSerif", Font.BOLD, 12));
        UIManager.put("Button.background", new Color(43, 47, 54));
        UIManager.put("Button.foreground", new Color(244, 247, 250));
        UIManager.put("TextField.background", new Color(32, 36, 43));
        UIManager.put("TextField.foreground", new Color(244, 247, 250));
        UIManager.put("ComboBox.background", new Color(31, 35, 42));
        UIManager.put("ComboBox.foreground", new Color(239, 243, 248));
        UIManager.put("List.background", new Color(25, 29, 35));
        UIManager.put("List.foreground", new Color(239, 243, 248));
        UIManager.put("List.selectionBackground", new Color(75, 83, 95));
        UIManager.put("List.selectionForeground", new Color(255, 255, 255));
        UIManager.put("ToolTip.background", new Color(35, 39, 46));
        UIManager.put("ToolTip.foreground", new Color(245, 247, 250));

        SwingUtilities.invokeLater(() -> {
            rootWindow = new JFrame("MeteoBetti");
            rootWindow.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            Rectangle screenBounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getMaximumWindowBounds();
            int windowWidth = Math.max(1, screenBounds.width / 2);
            int windowHeight = Math.max(1, screenBounds.height);
            rootWindow.setMinimumSize(new Dimension(
                    Math.min(360, windowWidth), Math.min(600, windowHeight)));
            rootWindow.setSize(windowWidth, windowHeight);
            rootWindow.setLocation(
                    screenBounds.x + (screenBounds.width - windowWidth) / 2,
                    screenBounds.y);

            JPanel backgroundWrapperPanel = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

                    if (currentWallpaperImage != null) {
                        double scale = Math.max((double) getWidth() / currentWallpaperImage.getWidth(this),
                                (double) getHeight() / currentWallpaperImage.getHeight(this));
                        int imageWidth = (int) Math.ceil(currentWallpaperImage.getWidth(this) * scale);
                        int imageHeight = (int) Math.ceil(currentWallpaperImage.getHeight(this) * scale);
                        g2.drawImage(currentWallpaperImage, (getWidth() - imageWidth) / 2,
                                (getHeight() - imageHeight) / 2, imageWidth, imageHeight, this);
                    } else {
                        g2.setPaint(new GradientPaint(0, 0, new Color(168, 214, 245),
                                0, getHeight(), new Color(88, 146, 196)));
                        g2.fillRect(0, 0, getWidth(), getHeight());
                    }
                    g2.setPaint(new GradientPaint(0, 0,
                            new Color(8, 11, 17, 72), 0, getHeight(),
                            new Color(8, 11, 17, 100)));
                    g2.fillRect(0, 0, getWidth(), getHeight());
                    g2.dispose();
                }
            };
            backgroundWrapperPanel.setLayout(new BorderLayout(0, 0));
            backgroundWrapperPanel.setBorder(new EmptyBorder(18, 18, 14, 18));

            JPanel topContainer = new JPanel();
            topContainer.setLayout(new BoxLayout(topContainer, BoxLayout.Y_AXIS));
            topContainer.setOpaque(false);

            JPanel topBar = new JPanel(new BorderLayout(8, 0));
            topBar.setOpaque(false);
            topBar.setMaximumSize(new Dimension(520, 44));
            topBar.setAlignmentX(Component.CENTER_ALIGNMENT);

            JLabel welcomeLbl = new JLabel("Ciao " + customUserName);
            welcomeLbl.setFont(uiFont(Font.BOLD, 16f));
            welcomeLbl.setForeground(new Color(18, 42, 64));

            JLabel brandLbl = new JLabel("MeteoBetti");
            brandLbl.setFont(uiFont(Font.PLAIN, 12f));
            brandLbl.setForeground(new Color(38, 72, 102, 210));

            JPanel identity = new JPanel();
            identity.setOpaque(false);
            identity.setLayout(new BoxLayout(identity, BoxLayout.Y_AXIS));
            welcomeLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
            brandLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
            identity.add(welcomeLbl);
            identity.add(brandLbl);

            JButton discreetMenuBtn = new JButton("Opzioni");
            styleDiscreetButton(discreetMenuBtn);

            topBar.add(identity, BorderLayout.WEST);
            topBar.add(discreetMenuBtn, BorderLayout.EAST);

            discreetDropdownPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
            discreetDropdownPanel.setOpaque(false);
            discreetDropdownPanel.setVisible(false);
            discreetDropdownPanel.setAlignmentX(Component.CENTER_ALIGNMENT);

            JButton editNameBtn = new JButton("Modifica nome");
            styleDiscreetButton(editNameBtn);
            editNameBtn.addActionListener(e -> {
                String input = JOptionPane.showInputDialog(rootWindow, "Inserisci il tuo nome:", customUserName);
                if (input != null && !input.trim().isEmpty()) {
                    customUserName = normalizeDisplayName(input);
                    welcomeLbl.setText("Ciao " + customUserName);
                    try {
                        saveDisplayName(userPreferences(), customUserName);
                    } catch (BackingStoreException | SecurityException | IllegalStateException ex) {
                        System.err.println("Impossibile salvare il nome utente: " + ex.getMessage());
                        JOptionPane.showMessageDialog(rootWindow,
                                "Il nome è stato aggiornato solo per questa sessione.\n"
                                        + "Non è stato possibile salvarlo nelle preferenze.",
                                "Preferenze non salvate", JOptionPane.WARNING_MESSAGE);
                    }
                }
            });
            discreetDropdownPanel.add(editNameBtn);

            discreetMenuBtn.addActionListener(e -> {
                isMenuVisible = !isMenuVisible;
                discreetDropdownPanel.setVisible(isMenuVisible);
                topContainer.revalidate();
                topContainer.repaint();
            });

            JPanel searchRow = new JPanel(new BorderLayout(8, 0));
            searchRow.setOpaque(false);
            searchRow.setMaximumSize(new Dimension(520, 48));
            searchRow.setAlignmentX(Component.CENTER_ALIGNMENT);

            searchTextField = new JTextField();
            searchTextField.setFont(uiFont(Font.PLAIN, 14f));
            searchTextField.setForeground(new Color(22, 48, 72));
            searchTextField.setCaretColor(new Color(46, 122, 186));
            searchTextField.setOpaque(false);
            searchTextField.setBorder(new EmptyBorder(10, 14, 10, 10));
            searchTextField.setToolTipText("Cerca comune (es. Roma, Milano, Alba)...");

            JButton searchBtn = new JButton("Cerca");
            styleDiscreetButton(searchBtn);

            JPanel searchGlass = createSearchGlassPanel();
            searchGlass.setLayout(new BorderLayout());
            JLabel searchHint = new JLabel("  ⌕");
            searchHint.setFont(uiFont(Font.PLAIN, 16f));
            searchHint.setForeground(new Color(78, 104, 128));
            searchGlass.add(searchHint, BorderLayout.WEST);
            searchGlass.add(searchTextField, BorderLayout.CENTER);
            searchRow.add(searchGlass, BorderLayout.CENTER);
            searchRow.add(searchBtn, BorderLayout.EAST);

            topContainer.add(topBar);
            topContainer.add(discreetDropdownPanel);
            topContainer.add(Box.createVerticalStrut(10));
            topContainer.add(searchRow);

            pageCardLayout = new CardLayout();
            mainCardContainer = new JPanel(pageCardLayout);
            mainCardContainer.setOpaque(false);
            mainCardContainer.setBorder(new EmptyBorder(16, 0, 10, 0));

            mainCardContainer.add(wrapPage(buildDashboardPage()), "DASHBOARD");
            mainCardContainer.add(wrapPage(buildAnalyticsPage()), "ANALITICA");
            mainCardContainer.add(wrapPage(buildRadarPage()), "RADAR");
            mainCardContainer.add(wrapPage(buildAirQualityPage()), "ARIA");
            mainCardContainer.add(wrapPage(buildDailyDetailPage()), "DETTAGLIO_GIORNO");

            appNavBar = new AppNavBar();

            JPanel centralAssembly = new JPanel(new BorderLayout(0, 8));
            centralAssembly.setOpaque(false);
            centralAssembly.add(topContainer, BorderLayout.NORTH);
            centralAssembly.add(mainCardContainer, BorderLayout.CENTER);
            centralAssembly.add(appNavBar, BorderLayout.SOUTH);

            backgroundWrapperPanel.add(centralAssembly, BorderLayout.CENTER);
            rootWindow.add(backgroundWrapperPanel);
            applyDarkTextTheme(rootWindow);
            rootWindow.setVisible(true);
            new Timer(15 * 60 * 1000, e -> backgroundWrapperPanel.repaint()).start();

            initPreciseLocationEngine(rootWindow);

            Runnable performSearch = () -> {
                String q = searchTextField.getText().trim();
                if (!q.isEmpty()) executeAsyncWeatherFetch(rootWindow, q);
            };
            searchBtn.addActionListener(e -> performSearch.run());
            searchTextField.addActionListener(e -> performSearch.run());
        });
    }

    private static Font uiFont(int style, float size) {
        if (uiFontFamily == null) {
            java.util.List<String> available = Arrays.asList(
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
            uiFontFamily = "SansSerif";
            for (String candidate : new String[]{"SF Pro Display", "SF Pro Text", "Helvetica Neue", "Segoe UI", "Inter"}) {
                if (available.contains(candidate)) {
                    uiFontFamily = candidate;
                    break;
                }
            }
        }
        return new Font(uiFontFamily, style, Math.round(size));
    }

    private static JComponent wrapPage(JPanel page) {
        JScrollPane scroll = new JScrollPane(page);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        styleScrollBar(scroll.getVerticalScrollBar());
        return scroll;
    }

    private static void paintFrostedCard(Graphics2D g2, int width, int height, int radius) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int cardHeight = Math.max(1, height - 4);
        int cardRadius = radius + 8;
        g2.setColor(new Color(0, 0, 0, 72));
        g2.fillRoundRect(3, 6, Math.max(1, width - 6), Math.max(1, height - 8),
                cardRadius, cardRadius);
        g2.setPaint(new GradientPaint(0, 0, new Color(83, 89, 100, 151),
                0, cardHeight, new Color(18, 21, 27, 176)));
        g2.fillRoundRect(0, 0, width, cardHeight, cardRadius, cardRadius);

        Shape cardShape = new java.awt.geom.RoundRectangle2D.Float(
                1, 1, Math.max(1, width - 3), Math.max(1, cardHeight - 2), cardRadius, cardRadius);
        Shape previousClip = g2.getClip();
        g2.clip(cardShape);
        g2.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 32),
                0, cardHeight * 0.46f, new Color(255, 255, 255, 0)));
        g2.fillRect(0, 0, width, cardHeight);
        g2.setClip(previousClip);
        paintGlassOutline(g2, 0, 0, Math.max(1, width - 1), Math.max(1, cardHeight - 1), cardRadius);
    }

    private static void paintGlassOutline(Graphics2D graphics, int x, int y, int width, int height, int radius) {
        Stroke previousStroke = graphics.getStroke();
        graphics.setPaint(new GradientPaint(x, y, new Color(242, 247, 255, 45),
                x + width, y + height, new Color(196, 215, 239, 18)));
        graphics.setStroke(new BasicStroke(0.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.draw(new java.awt.geom.RoundRectangle2D.Float(
                x + 0.5f, y + 0.5f, Math.max(1, width - 1), Math.max(1, height - 1), radius, radius));
        graphics.setStroke(previousStroke);
    }

    private static void applyDarkTextTheme(Component component) {
        Color foreground = new Color(239, 243, 248);
        if (component instanceof JLabel || component instanceof AbstractButton
                || component instanceof JTextField) {
            component.setForeground(foreground);
        }
        if (component instanceof JTextField) {
            component.setBackground(new Color(29, 33, 40, 220));
            ((JTextField) component).setCaretColor(new Color(224, 231, 239));
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                applyDarkTextTheme(child);
            }
        }
    }

    static BufferedImage decodeTileImage(byte[] imageBytes) throws Exception {
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(
                new ByteArrayInputStream(imageBytes))) {
            if (imageInput == null) {
                throw new IllegalArgumentException("Immagine tile non leggibile");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("Formato immagine tile non supportato");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_TILE_DIMENSION
                        || height > MAX_TILE_DIMENSION) {
                    throw new IllegalArgumentException("Dimensioni immagine tile non consentite");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    private static void styleDiscreetButton(JButton btn) {
        btn.setFont(uiFont(Font.BOLD, 12f));
        btn.setForeground(new Color(245, 247, 250));
        btn.setBackground(new Color(16, 19, 24, 190));
        btn.setOpaque(true);
        btn.setFocusPainted(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(255, 255, 255, 78), 1, true),
                new EmptyBorder(8, 14, 8, 14)));
    }

    private static <T> void styleDropdownText(JComboBox<T> selector) {
        Color textColor = new Color(226, 232, 240);
        Color menuColor = new Color(30, 35, 43);
        selector.setBackground(menuColor);
        selector.setForeground(textColor);
        selector.setOpaque(false);
        selector.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel item = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
                item.setForeground(isSelected ? Color.WHITE : textColor);
                item.setBackground(isSelected ? new Color(70, 83, 101) : menuColor);
                item.setBorder(new EmptyBorder(6, 10, 6, 10));
                return item;
            }
        });
        selector.setUI(new BasicComboBoxUI() {
            @Override
            protected JButton createArrowButton() {
                JButton arrow = new JButton("⌄");
                arrow.setFont(uiFont(Font.BOLD, 15f));
                arrow.setForeground(new Color(194, 205, 219));
                arrow.setBackground(new Color(30, 35, 43, 180));
                arrow.setBorder(BorderFactory.createEmptyBorder(0, 7, 2, 8));
                arrow.setFocusPainted(false);
                arrow.setContentAreaFilled(false);
                arrow.setOpaque(false);
                return arrow;
            }

            @Override
            protected ComboPopup createPopup() {
                BasicComboPopup popup = new BasicComboPopup(comboBox);
                popup.setOpaque(true);
                popup.setBackground(menuColor);
                popup.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(150, 164, 182, 90), 1, true),
                        new EmptyBorder(3, 3, 3, 3)));
                popup.getList().setBackground(menuColor);
                popup.getList().setForeground(textColor);
                return popup;
            }

            @Override
            public void paintCurrentValueBackground(Graphics graphics, Rectangle bounds, boolean hasFocus) {
            }
        });
        selector.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(219, 231, 246, 68), 1, true),
                new EmptyBorder(2, 7, 2, 5)));
    }

    private static JPanel createSearchGlassPanel() {
        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int width = getWidth();
                int height = getHeight();
                g2.setPaint(new GradientPaint(0, 0, new Color(83, 89, 100, 156),
                        0, height, new Color(12, 15, 20, 184)));
                g2.fillRoundRect(0, 0, width, height, 28, 28);
                Shape previousClip = g2.getClip();
                g2.clip(new java.awt.geom.RoundRectangle2D.Float(1, 1, width - 2, height - 2, 28, 28));
                g2.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 42),
                        0, height / 2f, new Color(255, 255, 255, 0)));
                g2.fillRect(0, 0, width, height / 2);
                g2.setClip(previousClip);
                paintGlassOutline(g2, 0, 0, width - 1, height - 1, 28);
                g2.dispose();
            }
        };
        panel.setOpaque(false);
        return panel;
    }

    private static void styleScrollBar(JScrollBar scrollBar) {
        scrollBar.setOpaque(false);
        scrollBar.setBorder(null);
        scrollBar.setUnitIncrement(22);
        scrollBar.setPreferredSize(new Dimension(9, 0));
        scrollBar.setUI(new BasicScrollBarUI() {
            @Override
            protected void configureScrollBarColors() {
                thumbColor = new Color(132, 141, 150, 164);
                trackColor = new Color(232, 235, 238, 64);
            }

            @Override
            protected JButton createDecreaseButton(int orientation) {
                return createInvisibleScrollButton();
            }

            @Override
            protected JButton createIncreaseButton(int orientation) {
                return createInvisibleScrollButton();
            }

            @Override
            protected void paintTrack(Graphics g, JComponent c, Rectangle bounds) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(trackColor);
                g2.fillRoundRect(bounds.x + 3, bounds.y, Math.max(1, bounds.width - 6),
                        bounds.height, 6, 6);
                g2.dispose();
            }

            @Override
            protected void paintThumb(Graphics g, JComponent c, Rectangle bounds) {
                if (bounds.isEmpty() || !scrollbar.isEnabled()) return;
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(isThumbRollover() || isDragging
                        ? new Color(103, 115, 127, 210)
                        : thumbColor);
                g2.fillRoundRect(bounds.x + 1, bounds.y + 2, Math.max(1, bounds.width - 4),
                        Math.max(1, bounds.height - 4), 8, 8);
                g2.dispose();
            }

            private JButton createInvisibleScrollButton() {
                JButton button = new JButton();
                button.setPreferredSize(new Dimension(0, 0));
                button.setMinimumSize(new Dimension(0, 0));
                button.setMaximumSize(new Dimension(0, 0));
                button.setOpaque(false);
                button.setBorderPainted(false);
                button.setFocusable(false);
                return button;
            }
        });
    }

    private static JPanel buildDashboardPage() {
        JPanel dashboard = new JPanel();
        dashboard.setLayout(new BoxLayout(dashboard, BoxLayout.Y_AXIS));
        dashboard.setOpaque(false);

        JPanel heroCard = createLiquidGlassCardPanel();
        heroCard.setLayout(new BoxLayout(heroCard, BoxLayout.Y_AXIS));
        heroCard.setBorder(new EmptyBorder(20, 22, 20, 22));
        heroCard.setMaximumSize(new Dimension(520, 210));
        heroCard.setAlignmentX(Component.CENTER_ALIGNMENT);
        heroCard.setCursor(new Cursor(Cursor.HAND_CURSOR));

        JLabel heroCardTitle = new JLabel("ADESSO");
        heroCardTitle.setFont(uiFont(Font.BOLD, 12f));
        heroCardTitle.setForeground(new Color(218, 226, 236));
        heroCardTitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        mainLocationDisplayLabel = new JLabel("Rilevamento località...");
        mainLocationDisplayLabel.setFont(uiFont(Font.BOLD, 18f));
        mainLocationDisplayLabel.setForeground(new Color(249, 250, 252));
        mainLocationDisplayLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel tempRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        tempRow.setOpaque(false);
        tempRow.setAlignmentX(Component.CENTER_ALIGNMENT);
        mainTempDisplayLabel = new JLabel("--°");
        mainTempDisplayLabel.setFont(uiFont(Font.BOLD, 58f));
        mainTempDisplayLabel.setForeground(new Color(255, 255, 255));
        heroIconLabel = new JLabel("☀️");
        heroIconLabel.setFont(uiFont(Font.PLAIN, 42f));
        tempRow.add(mainTempDisplayLabel);
        tempRow.add(heroIconLabel);

        conditionTextLabel = new JLabel("Sincronizzazione in corso...");
        conditionTextLabel.setFont(uiFont(Font.PLAIN, 14f));
        conditionTextLabel.setForeground(new Color(211, 220, 230));
        conditionTextLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        heroCard.add(heroCardTitle);
        heroCard.add(Box.createVerticalStrut(6));
        heroCard.add(mainLocationDisplayLabel);
        heroCard.add(Box.createVerticalStrut(4));
        heroCard.add(tempRow);
        heroCard.add(Box.createVerticalStrut(2));
        heroCard.add(conditionTextLabel);

        heroCard.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                String countryText = stationCountry == null || stationCountry.isBlank()
                        ? "non disponibile" : stationCountry;
                String locationText = escapeHtml(stationCity) + ", " + escapeHtml(countryText);
                String uvText = Double.isFinite(stationUvIndex)
                        ? String.format(Locale.ITALIAN, "%.1f UV", stationUvIndex) : "non disponibile";
                String apparentText = Double.isFinite(stationApparentTemp)
                        ? String.format(Locale.ITALIAN, "%.1f °C", stationApparentTemp) : "non disponibile";
                String humidityText = Double.isFinite(stationHumidity)
                        ? String.format(Locale.ITALIAN, "%.0f%%", stationHumidity) : "non disponibile";
                String pressureText = Double.isFinite(stationPressure)
                        ? String.format(Locale.ITALIAN, "%.0f hPa", stationPressure) : "non disponibile";
                String windText = Double.isFinite(stationWindSpeed)
                        ? String.format(Locale.ITALIAN, "%.1f km/h", stationWindSpeed) : "non disponibile";
                openTelemetryPopup(rootWindow, "Meteo e posizione",
                    "<html><body style='color:#e6eaf0; font-family:sans-serif; width:300px;'>" +
                    "<p style='font-size:16px; color:#ffffff;'><b>" + locationText + "</b></p>" +
                    "<p style='color:#aeb7c3;'>Condizioni attuali</p>" +
                    "<p><b>Temperatura:</b> " + String.format(Locale.ITALIAN, "%.1f °C", stationTemp) + "</p>" +
                    "<p><b>Percepita:</b> " + escapeHtml(apparentText) + "</p>" +
                    "<p><b>Umidità:</b> " + escapeHtml(humidityText) + "</p>" +
                    "<p><b>Pressione:</b> " + escapeHtml(pressureText) + "</p>" +
                    "<p><b>Vento:</b> " + escapeHtml(windText) + "</p>" +
                    "<p><b>Indice UV massimo previsto oggi:</b> " +
                    escapeHtml(uvText) + "</p>" +
                    "<p style='color:#aeb7c3; font-size:11px;'>Fonte meteo: "
                            + escapeHtml(weatherDataSourceName)
                            + (weatherDataSourceName.equals("MET Norway")
                            ? " · MET Norway (https://api.met.no/) · CC BY 4.0 " +
                            "(https://creativecommons.org/licenses/by/4.0/)" : "")
                            + "</p>" +
                    "<hr style='border:1px solid #555c67;'>" +
                    "<p style='color:#aeb7c3;'>Coordinate: " +
                    String.format(Locale.ITALIAN, "%.4f, %.4f", stationLat, stationLon) + "</p>" +
                    "<p style='color:#aeb7c3; font-size:11px;'>Usa la ricerca per consultare un altro comune.</p>" +
                    "</body></html>");
            }
        });

        JPanel timelineCard = createLiquidGlassCardPanel();
        timelineCard.setLayout(new BorderLayout(6, 8));
        timelineCard.setBorder(new EmptyBorder(14, 14, 14, 14));
        timelineCard.setMaximumSize(new Dimension(520, 176));
        timelineCard.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel timelineTitle = new JLabel("Prossime ore");
        timelineTitle.setFont(uiFont(Font.BOLD, 13f));
        timelineTitle.setForeground(new Color(239, 243, 248));
        timelineCard.add(timelineTitle, BorderLayout.NORTH);

        String[] cols = {"Orario", "Meteo", "Temp.", "Vento"};
        Object[][] initData = {{"--:--", "--", "--°C", "-- km/h"}};

        forecastTableModel = new DefaultTableModel(initData, cols) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };

        hourlyTimelineStrip = new HourlyTimelineStrip();
        timelineCard.add(hourlyTimelineStrip, BorderLayout.CENTER);

        JPanel metricsGrid = new JPanel(new GridLayout(2, 2, 12, 12));
        metricsGrid.setOpaque(false);
        metricsGrid.setMaximumSize(new Dimension(520, 236));
        metricsGrid.setPreferredSize(new Dimension(520, 236));
        metricsGrid.setAlignmentX(Component.CENTER_ALIGNMENT);

        humidityMetricBox = new TelemetryGaugeBox("💧 Umidità", "--%", 0,
                () -> openMetricInfo("Umidità relativa",
                        String.format(Locale.ITALIAN, "%.0f%%", stationHumidity),
                        "Percentuale di umidità presente nell'aria rispetto alla quantità massima che può contenere alla temperatura attuale."));
        uvMetricBox = new TelemetryGaugeBox("☀️ UV oggi", "-- UV", 0,
                () -> openMetricInfo("Indice UV massimo previsto oggi",
                        String.format(Locale.ITALIAN, "%.1f UV", stationUvIndex),
                        "Valore massimo previsto nell'arco della giornata. Un indice più alto indica una maggiore intensità della radiazione ultravioletta."));
        pressureMetricBox = new TelemetryGaugeBox("⏱️ Pressione", "-- hPa", 0,
                () -> openMetricInfo("Pressione atmosferica",
                        String.format(Locale.ITALIAN, "%.0f hPa", stationPressure),
                        "Pressione atmosferica rilevata per le coordinate selezionate."));
        airQualityMetricBox = new TelemetryGaugeBox("🍃 Qualità Aria", "N/D", 0,
                () -> openMetricInfo("Qualità dell'aria",
                        Double.isNaN(stationEuropeanAqi) ? "Dato non disponibile"
                                : String.format(Locale.ITALIAN, "%.0f AQI · %s",
                                stationEuropeanAqi, airQualityCategory(stationEuropeanAqi)),
                        "Indice europeo AQI e concentrazioni PM2.5, PM10 e NO₂ stimati dal modello CAMS tramite Open-Meteo. Sono dati modellistici, non misure di una centralina locale."));

        metricsGrid.add(humidityMetricBox);
        metricsGrid.add(uvMetricBox);
        metricsGrid.add(pressureMetricBox);
        metricsGrid.add(airQualityMetricBox);

        dashboard.add(heroCard);
        dashboard.add(Box.createVerticalStrut(12));
        dashboard.add(timelineCard);
        dashboard.add(Box.createVerticalStrut(12));
        dashboard.add(metricsGrid);
        dashboard.add(Box.createVerticalStrut(14));
        dailyForecastPanel = new DailyForecastPanel();
        dailyForecastPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
        dashboard.add(dailyForecastPanel);
        dashboard.add(Box.createVerticalStrut(10));

        return dashboard;
    }

    private static JPanel buildDailyDetailPage() {
        dailyDetailPanel = new DailyDetailPanel();
        return dailyDetailPanel;
    }

    static final class DailyForecast {
        private final String date;
        private final double meanTemperature;
        private final double precipitationProbability;
        private final double meanWindSpeed;
        private final double uvIndexMax;
        private final int weatherCode;
        private final String[] hourlyTimes;
        private final double[] hourlyTemperatures;
        private final double[] hourlyWeatherCodes;
        private final double[] hourlyPrecipitation;
        private final double[] hourlyWind;

        private DailyForecast(String date, double meanTemperature, double precipitationProbability,
                              double meanWindSpeed, double uvIndexMax, int weatherCode, String[] hourlyTimes,
                              double[] hourlyTemperatures, double[] hourlyWeatherCodes, double[] hourlyPrecipitation,
                              double[] hourlyWind) {
            this.date = date;
            this.meanTemperature = meanTemperature;
            this.precipitationProbability = precipitationProbability;
            this.meanWindSpeed = meanWindSpeed;
            this.uvIndexMax = uvIndexMax;
            this.weatherCode = weatherCode;
            this.hourlyTimes = hourlyTimes;
            this.hourlyTemperatures = hourlyTemperatures;
            this.hourlyWeatherCodes = hourlyWeatherCodes;
            this.hourlyPrecipitation = hourlyPrecipitation;
            this.hourlyWind = hourlyWind;
        }

        private String displayDate() {
            LocalDate localDate = LocalDate.parse(date);
            return localDate.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN));
        }

        private String shortDate() {
            LocalDate localDate = LocalDate.parse(date);
            if (localDate.equals(LocalDate.now())) return "Oggi";
            if (localDate.equals(LocalDate.now().plusDays(1))) return "Domani";
            return localDate.format(DateTimeFormatter.ofPattern("EEE d", Locale.ITALIAN));
        }

        private String weatherDescription() {
            return weatherCode < 0 ? "Condizione non disponibile" : resolveDescription(weatherCode);
        }
    }

    static class DailyForecastPanel extends JPanel {
        private final JPanel cards = new JPanel(new GridLayout(0, 2, 9, 9));

        DailyForecastPanel() {
            setOpaque(false);
            setLayout(new BorderLayout(0, 9));
            setBorder(new EmptyBorder(14, 14, 14, 14));
            setMaximumSize(new Dimension(520, Integer.MAX_VALUE));
            setPreferredSize(new Dimension(500, 410));

            JLabel title = new JLabel("PROSSIMI GIORNI");
            title.setFont(uiFont(Font.BOLD, 13f));
            title.setForeground(new Color(241, 245, 249));
            JLabel subtitle = new JLabel("Medie giornaliere · seleziona un giorno per i dettagli");
            subtitle.setFont(uiFont(Font.PLAIN, 12f));
            subtitle.setForeground(new Color(201, 210, 220));
            JPanel heading = new JPanel();
            heading.setOpaque(false);
            heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
            heading.add(title);
            heading.add(Box.createVerticalStrut(3));
            heading.add(subtitle);
            add(heading, BorderLayout.NORTH);

            cards.setOpaque(false);
            add(cards, BorderLayout.CENTER);
        }

        void updateForecasts(DailyForecast[] forecasts) {
            cards.removeAll();
            for (DailyForecast forecast : forecasts) {
                JButton dayCard = createDayCard(forecast);
                dayCard.addActionListener(e -> openDailyDetail(forecast));
                cards.add(dayCard);
            }
            revalidate();
            repaint();
        }

        private JButton createDayCard(DailyForecast forecast) {
            String average = Double.isFinite(forecast.meanTemperature)
                    ? String.format(Locale.ITALIAN, "%.1f°", forecast.meanTemperature) : "--°";
            String precipitation = Double.isFinite(forecast.precipitationProbability)
                    ? String.format(Locale.ITALIAN, "%.0f%%", forecast.precipitationProbability) : "--";
            String wind = Double.isFinite(forecast.meanWindSpeed)
                    ? String.format(Locale.ITALIAN, "%.0f km/h", forecast.meanWindSpeed) : "-- km/h";
            String icon = forecast.weatherCode < 0 ? "—" : resolveEmoji(forecast.weatherCode);
            String html = "<html><div style='text-align:left; color:#f1f4f8; padding:2px; font-size:12px;'>" +
                    "<b>" + escapeHtml(forecast.shortDate()) + "</b> <span style='font-size:16px;'>" + icon + "</span><br>" +
                    "<b style='font-size:16px;'>" + average + "</b> &nbsp; Pioggia " + precipitation + "<br>" +
                    "<span style='color:#c4ccd6;'>Vento medio " + wind + "</span></div></html>";
            JButton card = new JButton(html) {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    paintFrostedCard(g2, getWidth(), getHeight(), 22);
                    if (getModel().isRollover()) {
                        g2.setColor(new Color(255, 255, 255, 22));
                        g2.fillRoundRect(2, 2, getWidth() - 4, getHeight() - 6, 20, 20);
                    }
                    g2.dispose();
                    super.paintComponent(g);
                }
            };
            card.setHorizontalAlignment(SwingConstants.LEFT);
            card.setFont(uiFont(Font.PLAIN, 12f));
            card.setForeground(new Color(41, 69, 92));
            card.setFocusPainted(false);
            card.setContentAreaFilled(false);
            card.setBorderPainted(false);
            card.setOpaque(false);
            card.setCursor(new Cursor(Cursor.HAND_CURSOR));
            card.setBorder(new EmptyBorder(8, 9, 8, 9));
            card.setToolTipText("Apri i dettagli orari di " + forecast.displayDate());
            return card;
        }
    }

    private static void openDailyDetail(DailyForecast forecast) {
        if (dailyDetailPanel == null || mainCardContainer == null) return;
        dailyDetailPanel.showForecast(forecast);
        if (appNavBar != null) appNavBar.select("DASHBOARD");
        pageCardLayout.show(mainCardContainer, "DETTAGLIO_GIORNO");
    }

    static class DailyDetailPanel extends JPanel {
        private final JLabel dateLabel = new JLabel("Dettaglio giornaliero");
        private final JLabel temperatureLabel = new JLabel("--°");
        private final JLabel conditionLabel = new JLabel("Seleziona un giorno dalla previsione");
        private final JLabel summaryLabel = new JLabel("Temperatura media · probabilità pioggia · vento medio");
        private final DailyDetailChartsPanel charts = new DailyDetailChartsPanel();
        private final HourlyTimelineStrip hourlyForecast = new HourlyTimelineStrip();

        DailyDetailPanel() {
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

            JButton back = new JButton("←  Torna alle previsioni");
            styleDiscreetButton(back);
            back.setAlignmentX(Component.LEFT_ALIGNMENT);
            back.addActionListener(e -> {
                if (appNavBar != null) appNavBar.select("DASHBOARD");
                else pageCardLayout.show(mainCardContainer, "DASHBOARD");
            });
            add(back);
            add(Box.createVerticalStrut(12));

            JPanel hero = createLiquidGlassCardPanel();
            hero.setLayout(new BoxLayout(hero, BoxLayout.Y_AXIS));
            hero.setBorder(new EmptyBorder(18, 18, 18, 18));
            hero.setMaximumSize(new Dimension(520, 188));
            hero.setAlignmentX(Component.CENTER_ALIGNMENT);
            dateLabel.setFont(uiFont(Font.BOLD, 18f));
            dateLabel.setForeground(new Color(31, 57, 78));
            dateLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            temperatureLabel.setFont(uiFont(Font.BOLD, 54f));
            temperatureLabel.setForeground(new Color(250, 251, 253));
            temperatureLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            conditionLabel.setFont(uiFont(Font.PLAIN, 13f));
            conditionLabel.setForeground(new Color(214, 222, 232));
            conditionLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            summaryLabel.setFont(uiFont(Font.PLAIN, 12f));
            summaryLabel.setForeground(new Color(198, 207, 218));
            summaryLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            hero.add(dateLabel);
            hero.add(Box.createVerticalStrut(4));
            hero.add(temperatureLabel);
            hero.add(conditionLabel);
            hero.add(Box.createVerticalStrut(5));
            hero.add(summaryLabel);
            add(hero);
            add(Box.createVerticalStrut(12));

            JLabel chartTitle = new JLabel("SINTESI DELLA GIORNATA");
            chartTitle.setFont(uiFont(Font.BOLD, 12f));
            chartTitle.setForeground(new Color(237, 242, 247));
            chartTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
            add(chartTitle);
            add(Box.createVerticalStrut(8));
            JLabel hourlyTitle = new JLabel("PREVISIONI ORARIE");
            hourlyTitle.setFont(uiFont(Font.BOLD, 12f));
            hourlyTitle.setForeground(new Color(237, 242, 247));
            hourlyTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
            add(hourlyTitle);
            add(Box.createVerticalStrut(6));
            hourlyForecast.setLayout(new GridLayout(1, 0, 8, 0));
            hourlyForecast.setPreferredSize(new Dimension(24 * 100, 118));
            JScrollPane hourlyScroll = new JScrollPane(hourlyForecast,
                    JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            hourlyScroll.setOpaque(false);
            hourlyScroll.getViewport().setOpaque(false);
            hourlyScroll.setBorder(BorderFactory.createEmptyBorder());
            hourlyScroll.setAlignmentX(Component.CENTER_ALIGNMENT);
            hourlyScroll.setPreferredSize(new Dimension(460, 128));
            hourlyScroll.setMaximumSize(new Dimension(520, 128));
            add(hourlyScroll);
            add(Box.createVerticalStrut(10));
            charts.setPreferredSize(new Dimension(460, 220));
            charts.setMaximumSize(new Dimension(520, 220));
            charts.setAlignmentX(Component.CENTER_ALIGNMENT);
            add(charts);
        }

        void showForecast(DailyForecast forecast) {
            dateLabel.setText(capitalize(forecast.displayDate()));
            temperatureLabel.setText(Double.isFinite(forecast.meanTemperature)
                    ? String.format(Locale.ITALIAN, "%.1f°", forecast.meanTemperature) : "--°");
            conditionLabel.setText(forecast.weatherDescription()
                    + (forecast.weatherCode >= 0 ? "  " + resolveEmoji(forecast.weatherCode) : ""));
            String precipitation = Double.isFinite(forecast.precipitationProbability)
                    ? String.format(Locale.ITALIAN, "%.0f%%", forecast.precipitationProbability) : "--";
            String wind = Double.isFinite(forecast.meanWindSpeed)
                    ? String.format(Locale.ITALIAN, "%.1f km/h", forecast.meanWindSpeed) : "--";
            summaryLabel.setText("Pioggia " + precipitation + "  ·  Vento medio " + wind);
            hourlyForecast.updateFromForecast(forecast);
            charts.updateForecast(forecast);
        }

        private String capitalize(String text) {
            if (text.isEmpty()) return text;
            return text.substring(0, 1).toUpperCase(Locale.ITALIAN) + text.substring(1);
        }
    }

    static class DailyDetailChartsPanel extends JPanel {
        private DailyForecast selectedForecast;
        private final TelemetryGaugeBox temperatureGauge =
                new TelemetryGaugeBox("Temp. media", "--°", 0,
                        () -> showMetricInfo("Temperatura media",
                                formatSelectedValue(selectedForecast == null ? Double.NaN
                                        : selectedForecast.meanTemperature, "%.1f °C"),
                                "Temperatura media prevista per l'intera giornata."));
        private final TelemetryGaugeBox precipitationGauge =
                new TelemetryGaugeBox("Pioggia max", "--%", 0,
                        () -> showMetricInfo("Probabilità massima di precipitazioni",
                                formatSelectedValue(selectedForecast == null ? Double.NaN
                                        : selectedForecast.precipitationProbability, "%.0f%%"),
                                "Probabilità massima prevista di precipitazioni nel corso della giornata."));
        private final TelemetryGaugeBox windGauge =
                new TelemetryGaugeBox("Vento medio", "--", 0,
                        () -> showMetricInfo("Vento medio",
                                formatSelectedValue(selectedForecast == null ? Double.NaN
                                        : selectedForecast.meanWindSpeed, "%.1f km/h"),
                                "Velocità media del vento prevista durante la giornata."));
        private final TelemetryGaugeBox uvGauge =
                new TelemetryGaugeBox("UV massimo", "-- UV", 0,
                        () -> showMetricInfo("Indice UV massimo",
                                formatSelectedValue(selectedForecast == null ? Double.NaN
                                        : selectedForecast.uvIndexMax, "%.1f UV"),
                                "Indice UV massimo previsto per la giornata. Valori più alti indicano maggiore intensità della radiazione ultravioletta."));

        DailyDetailChartsPanel() {
            setOpaque(false);
            setLayout(new GridLayout(2, 2, 10, 10));
            add(temperatureGauge);
            add(precipitationGauge);
            add(windGauge);
            add(uvGauge);
        }

        void updateForecast(DailyForecast forecast) {
            selectedForecast = forecast;
            String temperature = Double.isFinite(forecast.meanTemperature)
                    ? String.format(Locale.ITALIAN, "%.1f°", forecast.meanTemperature) : "N/D";
            String precipitation = Double.isFinite(forecast.precipitationProbability)
                    ? String.format(Locale.ITALIAN, "%.0f%%", forecast.precipitationProbability) : "N/D";
            String wind = Double.isFinite(forecast.meanWindSpeed)
                    ? String.format(Locale.ITALIAN, "%.1f", forecast.meanWindSpeed) : "N/D";
            String uv = Double.isFinite(forecast.uvIndexMax)
                    ? String.format(Locale.ITALIAN, "%.1f UV", forecast.uvIndexMax) : "N/D";
            int temperaturePercent = Double.isFinite(forecast.meanTemperature)
                    ? (int) Math.round(Math.max(0, Math.min(100,
                    (forecast.meanTemperature + 20) / 65 * 100))) : 0;
            int precipitationPercent = Double.isFinite(forecast.precipitationProbability)
                    ? (int) Math.round(Math.max(0, Math.min(100, forecast.precipitationProbability))) : 0;
            int windPercent = Double.isFinite(forecast.meanWindSpeed)
                    ? (int) Math.round(Math.max(0, Math.min(100, forecast.meanWindSpeed / 60 * 100))) : 0;
            int uvPercent = Double.isFinite(forecast.uvIndexMax)
                    ? (int) Math.round(Math.max(0, Math.min(100, forecast.uvIndexMax / 11 * 100))) : 0;
            temperatureGauge.updateGaugeData(temperature, temperaturePercent);
            precipitationGauge.updateGaugeData(precipitation, precipitationPercent);
            windGauge.updateGaugeData(wind, windPercent);
            uvGauge.updateGaugeData(uv, uvPercent);
        }

        private void showMetricInfo(String title, String value, String description) {
            if (selectedForecast == null) return;
            openMetricInfo(title + " · " + selectedForecast.shortDate(), value, description);
        }

        private String formatSelectedValue(double value, String format) {
            return Double.isFinite(value)
                    ? String.format(Locale.ITALIAN, format, value) : "Dato non disponibile";
        }
    }
    private static JPanel buildAnalyticsPage() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);

        JLabel title = createSectionTitle("Analitica · 7 giorni");
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(title);
        JLabel subtitle = new JLabel("Andamento previsto · seleziona un grafico per aprire il dettaglio del giorno");
        subtitle.setFont(uiFont(Font.PLAIN, 12f));
        subtitle.setForeground(new Color(199, 207, 217));
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(Box.createVerticalStrut(4));
        panel.add(subtitle);
        panel.add(Box.createVerticalStrut(14));

        analyticsForecastPanel = new AnalyticsForecastPanel();
        analyticsForecastPanel.setPreferredSize(new Dimension(500, 440));
        analyticsForecastPanel.setMaximumSize(new Dimension(520, 440));
        analyticsForecastPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(analyticsForecastPanel);

        return panel;
    }

    static class AnalyticsForecastPanel extends JPanel {
        private final AnalyticsMetricChart[] charts = {
                new AnalyticsMetricChart("Temperatura media", "°C", new Color(226, 125, 67), true),
                new AnalyticsMetricChart("Probabilità pioggia", "%", new Color(57, 139, 198), false),
                new AnalyticsMetricChart("Vento medio", "km/h", new Color(53, 153, 125), false),
                new AnalyticsMetricChart("Indice UV massimo", "UV", new Color(204, 151, 56), false)
        };
        private DailyForecast[] forecasts = new DailyForecast[0];

        AnalyticsForecastPanel() {
            setOpaque(false);
            setLayout(new GridLayout(2, 2, 10, 10));
            for (AnalyticsMetricChart chart : charts) add(chart);
        }

        void updateForecasts(DailyForecast[] newForecasts) {
            forecasts = newForecasts.clone();
            double[][] series = new double[charts.length][forecasts.length];
            String[] dates = new String[forecasts.length];
            for (int i = 0; i < forecasts.length; i++) {
                DailyForecast forecast = forecasts[i];
                dates[i] = forecast.date;
                series[0][i] = forecast.meanTemperature;
                series[1][i] = forecast.precipitationProbability;
                series[2][i] = forecast.meanWindSpeed;
                series[3][i] = forecast.uvIndexMax;
            }
            for (int i = 0; i < charts.length; i++) charts[i].setData(dates, series[i]);
        }

        private final class AnalyticsMetricChart extends JPanel {
            private final String title;
            private final String unit;
            private final Color accent;
            private final boolean smoothCurve;
            private String[] dates = new String[0];
            private double[] data = new double[0];
            private int hoveredIndex = -1;

            AnalyticsMetricChart(String title, String unit, Color accent, boolean smoothCurve) {
                this.title = title;
                this.unit = unit;
                this.accent = accent;
                this.smoothCurve = smoothCurve;
                setOpaque(false);
                setCursor(new Cursor(Cursor.HAND_CURSOR));
                setToolTipText(null);
                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent event) {
                        int index = closestDayIndex(event.getX());
                        if (index >= 0 && index < forecasts.length) openDailyDetail(forecasts[index]);
                    }

                    @Override
                    public void mouseExited(MouseEvent event) {
                        hoveredIndex = -1;
                        repaint();
                    }
                });
                addMouseMotionListener(new MouseAdapter() {
                    @Override
                    public void mouseMoved(MouseEvent event) {
                        int nextIndex = closestPointIndex(event.getPoint());
                        if (nextIndex != hoveredIndex) {
                            hoveredIndex = nextIndex;
                            repaint();
                        }
                    }
                });
            }

            void setData(String[] newDates, double[] newData) {
                dates = newDates.clone();
                data = newData.clone();
                repaint();
            }

            private int closestDayIndex(int x) {
                if (data.length == 0) return -1;
                int left = 22;
                int right = Math.max(left + 1, getWidth() - 22);
                double position = (double) (x - left) / (right - left);
                return Math.max(0, Math.min(data.length - 1,
                        (int) Math.round(position * (data.length - 1))));
            }

            private int closestPointIndex(Point point) {
                if (data.length == 0) return -1;
                int left = 22;
                int right = Math.max(left + 1, getWidth() - 22);
                int top = 40;
                int bottom = getHeight() - 30;
                double[] bounds = chartValueBounds();
                double range = Math.max(1, bounds[1] - bounds[0]);
                int closest = -1;
                double closestDistance = 12.0;
                for (int i = 0; i < data.length; i++) {
                    if (!Double.isFinite(data[i])) continue;
                    double x = data.length == 1 ? (left + right) / 2.0
                            : left + (double) i * (right - left) / (data.length - 1);
                    double y = bottom - ((data[i] - bounds[0]) / range) * (bottom - top);
                    double distance = Point.distance(point.x, point.y, x, y);
                    if (distance <= closestDistance) {
                        closestDistance = distance;
                        closest = i;
                    }
                }
                return closest;
            }

            private double[] chartValueBounds() {
                double min = Double.POSITIVE_INFINITY;
                double max = Double.NEGATIVE_INFINITY;
                for (double value : data) {
                    if (Double.isFinite(value)) {
                        min = Math.min(min, value);
                        max = Math.max(max, value);
                    }
                }
                if (!Double.isFinite(min)) return new double[]{0, 1};
                double padding = Math.max(1.0, (max - min) * 0.22);
                if (title.equals("Probabilità pioggia")) {
                    return new double[]{0, 100};
                }
                if (title.equals("Indice UV massimo")) {
                    return new double[]{0, Math.max(11, max + padding)};
                }
                return new double[]{min - padding, max + padding};
            }

            @Override
            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                Graphics2D g2 = (Graphics2D) graphics.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                paintFrostedCard(g2, getWidth(), getHeight(), 22);
                g2.setFont(uiFont(Font.BOLD, 12f));
                g2.setColor(new Color(242, 245, 249));
                g2.drawString(title, 14, 22);

                double sum = 0;
                int availableValues = 0;
                for (double point : data) {
                    if (Double.isFinite(point)) {
                        sum += point;
                        availableValues++;
                    }
                }
                if (availableValues > 0) {
                    String value = String.format(Locale.ITALIAN, "Media %.1f %s",
                            sum / availableValues, unit);
                    g2.setFont(uiFont(Font.BOLD, 12f));
                    g2.setColor(new Color(213, 222, 232));
                    g2.drawString(value, getWidth() - g2.getFontMetrics().stringWidth(value) - 14, 22);
                }
                if (data.length == 0 || availableValues == 0) {
                    g2.setFont(uiFont(Font.PLAIN, 12f));
                    g2.setColor(new Color(197, 205, 215));
                    g2.drawString("In attesa delle previsioni giornaliere", 14, getHeight() / 2);
                    g2.dispose();
                    return;
                }

                int left = 22;
                int right = Math.max(left + 1, getWidth() - 22);
                int top = 40;
                int bottom = getHeight() - 30;
                double[] bounds = chartValueBounds();
                double min = bounds[0];
                double max = bounds[1];
                double range = Math.max(1, max - min);
                Path2D line = new Path2D.Double();
                Path2D fill = new Path2D.Double();
                boolean started = false;
                double previousX = left;
                double previousY = bottom;
                for (int i = 0; i < data.length; i++) {
                    if (!Double.isFinite(data[i])) continue;
                    double x = data.length == 1 ? (left + right) / 2.0
                            : left + (double) i * (right - left) / (data.length - 1);
                    double y = bottom - ((data[i] - min) / range) * (bottom - top);
                    if (!started) {
                        line.moveTo(x, y);
                        fill.moveTo(x, bottom);
                        fill.lineTo(x, y);
                        started = true;
                    } else if (smoothCurve) {
                        double midX = (previousX + x) / 2;
                        double midY = (previousY + y) / 2;
                        line.quadTo(previousX, previousY, midX, midY);
                        fill.quadTo(previousX, previousY, midX, midY);
                    } else {
                        line.lineTo(x, y);
                        fill.lineTo(x, y);
                    }
                    previousX = x;
                    previousY = y;
                }
                if (started) {
                    line.lineTo(previousX, previousY);
                    fill.lineTo(previousX, previousY);
                    fill.lineTo(previousX, bottom);
                    fill.closePath();
                    g2.setPaint(new GradientPaint(0, top, new Color(accent.getRed(), accent.getGreen(),
                            accent.getBlue(), 92), 0, bottom, new Color(accent.getRed(),
                            accent.getGreen(), accent.getBlue(), 10)));
                    g2.fill(fill);
                    g2.setColor(new Color(91, 111, 127, 75));
                    g2.drawLine(left, bottom, right, bottom);
                    g2.setColor(accent);
                    g2.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.draw(line);
                }

                double hoveredX = -1;
                double hoveredY = -1;
                g2.setFont(uiFont(Font.PLAIN, 9f));
                g2.setColor(new Color(221, 227, 235));
                for (int i = 0; i < data.length; i++) {
                    if (!Double.isFinite(data[i])) continue;
                    double x = data.length == 1 ? (left + right) / 2.0
                            : left + (double) i * (right - left) / (data.length - 1);
                    double y = bottom - ((data[i] - min) / range) * (bottom - top);
                    if (i == hoveredIndex) {
                        hoveredX = x;
                        hoveredY = y;
                    }
                    g2.setColor(Color.WHITE);
                    g2.fillOval((int) x - (i == hoveredIndex ? 7 : 4),
                            (int) y - (i == hoveredIndex ? 7 : 4),
                            i == hoveredIndex ? 14 : 8, i == hoveredIndex ? 14 : 8);
                    g2.setColor(accent.darker());
                    g2.fillOval((int) x - 2, (int) y - 2, 4, 4);
                    if (i < dates.length) {
                        String day = LocalDate.parse(dates[i])
                                .format(DateTimeFormatter.ofPattern("EE", Locale.ITALIAN));
                        String label = day.substring(0, 1).toUpperCase(Locale.ITALIAN) + day.substring(1);
                        g2.setFont(uiFont(Font.BOLD, 9f));
                        g2.setColor(new Color(221, 227, 235));
                        int labelWidth = g2.getFontMetrics().stringWidth(label);
                        g2.drawString(label, (int) x - labelWidth / 2, getHeight() - 9);
                    }
                }
                if (hoveredIndex >= 0 && hoveredIndex < data.length
                        && Double.isFinite(data[hoveredIndex]) && hoveredX >= 0) {
                    String day = LocalDate.parse(dates[hoveredIndex])
                            .format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ITALIAN));
                    day = day.substring(0, 1).toUpperCase(Locale.ITALIAN) + day.substring(1);
                    String preciseValue = String.format(Locale.ITALIAN,
                            title.equals("Probabilità pioggia") ? "%.0f %s" : "%.1f %s",
                            data[hoveredIndex], unit);
                    String callout = day + " · " + preciseValue;
                    g2.setFont(uiFont(Font.BOLD, 11f));
                    FontMetrics metrics = g2.getFontMetrics();
                    int calloutWidth = metrics.stringWidth(callout) + 16;
                    int calloutHeight = metrics.getHeight() + 8;
                    int calloutX = (int) Math.round(hoveredX - calloutWidth / 2.0);
                    calloutX = Math.max(5, Math.min(getWidth() - calloutWidth - 5, calloutX));
                    int calloutY = (int) Math.round(hoveredY - calloutHeight - 10);
                    if (calloutY < 28) calloutY = (int) Math.round(hoveredY + 12);
                    g2.setColor(new Color(255, 220, 112, 248));
                    g2.fillRoundRect(calloutX, calloutY, calloutWidth, calloutHeight, 12, 12);
                    g2.setColor(new Color(22, 25, 30));
                    g2.drawString(callout, calloutX + 8, calloutY + metrics.getAscent() + 4);
                }
                g2.dispose();
            }

        }
    }

    private static JPanel buildRadarPage() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);

        JLabel title = createSectionTitle("Radar e modelli");
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(title);
        panel.add(Box.createVerticalStrut(8));
        radarDateSelector = createForecastDateSelector();
        radarDateSelector.addActionListener(e -> {
            if (!updatingForecastDateSelectors && radarMapPanel != null
                    && radarDateSelector.getSelectedItem() != null) {
                radarMapPanel.refresh(stationLat, stationLon);
            }
        });
        panel.add(createDateSelectorRow("Giorno della mappa", radarDateSelector));
        panel.add(Box.createVerticalStrut(6));

        JPanel radarBox = createLiquidGlassCardPanel();
        radarBox.setLayout(new BorderLayout());
        radarBox.setBorder(new EmptyBorder(8, 8, 8, 8));
        radarBox.setMaximumSize(new Dimension(500, 470));
        radarBox.setAlignmentX(Component.CENTER_ALIGNMENT);
        radarMapPanel = new RadarMapPanel();
        radarBox.add(radarMapPanel, BorderLayout.CENTER);

        panel.add(radarBox);
        JPanel attribution = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 4));
        attribution.setOpaque(false);
        JLabel source = new JLabel("Radar: RainViewer  •  Modelli: Open-Meteo  •  Mappa: © OpenStreetMap");
        source.setFont(new Font("SansSerif", Font.PLAIN, 10));
        source.setForeground(new Color(54, 73, 89));
        attribution.add(source);
        panel.add(attribution);
        return panel;
    }

    private static JPanel buildAirQualityPage() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);

        JLabel title = createSectionTitle("Qualità dell'aria");
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(title);
        panel.add(Box.createVerticalStrut(8));
        airQualityDateSelector = createForecastDateSelector();
        airQualityDateSelector.addActionListener(e -> {
            if (updatingForecastDateSelectors) return;
            ForecastDateOption selected = (ForecastDateOption) airQualityDateSelector.getSelectedItem();
            if (selected != null && airQualityTrendsPanel != null) {
                airQualityTrendsPanel.setSelectedDate(selected.date);
                if (airQualityTrendsTitleLabel != null) {
                    airQualityTrendsTitleLabel.setText("Andamento orario · " + selected.displayDate());
                }
            }
        });
        panel.add(createDateSelectorRow("Giorno delle previsioni", airQualityDateSelector));
        panel.add(Box.createVerticalStrut(6));

        JPanel airBox = createLiquidGlassCardPanel();
        airBox.setLayout(new BoxLayout(airBox, BoxLayout.Y_AXIS));
        airBox.setBorder(new EmptyBorder(18, 22, 18, 22));
        airBox.setMaximumSize(new Dimension(500, 450));
        airBox.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel airTitle = new JLabel("In questa zona");
        airTitle.setFont(uiFont(Font.BOLD, 12f));
        airTitle.setForeground(new Color(224, 231, 239));
        airQualitySummaryLabel = new JLabel("In attesa dei dati della località...");
        airQualitySummaryLabel.setFont(uiFont(Font.BOLD, 28f));
        airQualitySummaryLabel.setForeground(new Color(250, 251, 253));
        airQualityAqiLabel = createAirDataLabel("Indice europeo AQI: --");
        airQualityPm25Label = createAirDataLabel("PM2.5: -- µg/m³");
        airQualityPm10Label = createAirDataLabel("PM10: -- µg/m³");
        airQualityNo2Label = createAirDataLabel("NO₂: -- µg/m³");
        JLabel sourceLabel = createAirDataLabel("Fonte: Open-Meteo / CAMS");
        sourceLabel.setForeground(new Color(186, 196, 207));
        airQualityTrendsPanel = new AirQualityTrendsPanel();
        airQualityTrendsPanel.setPreferredSize(new Dimension(450, 220));
        airQualityTrendsPanel.setMaximumSize(new Dimension(450, 220));
        airQualityTrendsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        airBox.add(airTitle);
        airBox.add(Box.createVerticalStrut(9));
        airBox.add(airQualitySummaryLabel);
        airBox.add(Box.createVerticalStrut(5));
        airBox.add(airQualityAqiLabel);
        airBox.add(airQualityPm25Label);
        airBox.add(airQualityPm10Label);
        airBox.add(airQualityNo2Label);
        airBox.add(Box.createVerticalStrut(8));
        airQualityTrendsTitleLabel = createAirDataLabel("Andamento orario · oggi");
        airQualityTrendsTitleLabel.setFont(uiFont(Font.BOLD, 12f));
        airQualityTrendsTitleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        airBox.add(airQualityTrendsTitleLabel);
        airBox.add(airQualityTrendsPanel);
        airBox.add(Box.createVerticalStrut(8));
        JLabel modelNote = createAirDataLabel("Previsione modellistica regionale, non sensore locale.");
        modelNote.setFont(new Font("SansSerif", Font.ITALIC, 12));
        modelNote.setForeground(new Color(195, 204, 214));
        airBox.add(modelNote);
        airBox.add(Box.createVerticalStrut(4));
        airBox.add(sourceLabel);

        panel.add(airBox);
        return panel;
    }

    private static JPanel createDateSelectorRow(String labelText, JComboBox<ForecastDateOption> selector) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(500, 36));
        row.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel label = createAirDataLabel(labelText);
        label.setFont(uiFont(Font.BOLD, 12f));
        selector.setFont(uiFont(Font.PLAIN, 12f));
        styleDropdownText(selector);
        row.add(label, BorderLayout.WEST);
        row.add(selector, BorderLayout.CENTER);
        return row;
    }

    private static JComboBox<ForecastDateOption> createForecastDateSelector() {
        JComboBox<ForecastDateOption> selector = new JComboBox<>();
        selector.setMaximumSize(new Dimension(280, 32));
        styleDropdownText(selector);
        setForecastDates(selector, new String[]{LocalDate.now().toString()});
        return selector;
    }

    private static void setForecastDates(JComboBox<ForecastDateOption> selector, String[] dates) {
        if (selector == null) return;
        ForecastDateOption current = (ForecastDateOption) selector.getSelectedItem();
        String selectedDate = current == null ? LocalDate.now().toString() : current.date;
        updatingForecastDateSelectors = true;
        try {
            selector.removeAllItems();
            boolean selectedDateAvailable = false;
            for (String date : dates) {
                if (date == null || date.length() < 10) continue;
                ForecastDateOption option = new ForecastDateOption(date.substring(0, 10));
                selector.addItem(option);
                if (option.date.equals(selectedDate)) selectedDateAvailable = true;
            }
            if (selector.getItemCount() > 0) {
                if (selectedDateAvailable) {
                    for (int i = 0; i < selector.getItemCount(); i++) {
                        if (selector.getItemAt(i).date.equals(selectedDate)) {
                            selector.setSelectedIndex(i);
                            break;
                        }
                    }
                } else {
                    selector.setSelectedIndex(0);
                }
            }
        } finally {
            updatingForecastDateSelectors = false;
        }
    }

    private static String[] uniqueDates(String[] times) {
        java.util.LinkedHashSet<String> dates = new java.util.LinkedHashSet<>();
        for (String time : times) {
            if (time != null && time.length() >= 10) dates.add(time.substring(0, 10));
        }
        return dates.toArray(new String[0]);
    }

    private static final class ForecastDateOption {
        private final String date;

        private ForecastDateOption(String date) {
            this.date = date;
        }

        private String displayDate() {
            LocalDate localDate = LocalDate.parse(date);
            if (localDate.equals(LocalDate.now())) return "Oggi";
            if (localDate.equals(LocalDate.now().plusDays(1))) return "Domani";
            String display = localDate.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN));
            return display.substring(0, 1).toUpperCase(Locale.ITALIAN) + display.substring(1);
        }

        @Override
        public String toString() {
            return displayDate();
        }
    }

    private static JLabel createAirDataLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(uiFont(Font.PLAIN, 14f));
        label.setForeground(new Color(224, 231, 239));
        return label;
    }

    static class AirQualityTrendsPanel extends JPanel {
        private final String[] names = {"AQI europeo", "PM2.5 µg/m³", "PM10 µg/m³", "NO₂ µg/m³"};
        private final Color[] colors = {
                new Color(83, 132, 165), new Color(206, 139, 74),
                new Color(117, 151, 101), new Color(147, 113, 164)
        };
        private String[] times = {};
        private double[][] values = new double[4][];
        private String selectedDate = LocalDate.now().toString();

        AirQualityTrendsPanel() {
            setOpaque(false);
        }

        void updateData(String[] newTimes, double[][] newValues) {
            times = newTimes.clone();
            values = new double[newValues.length][];
            for (int i = 0; i < newValues.length; i++) {
                values[i] = newValues[i].clone();
            }
            updateSelectedDay();
            repaint();
        }

        void setSelectedDate(String date) {
            selectedDate = date;
            updateSelectedDay();
        }

        private void updateSelectedDay() {
            int first = -1;
            int last = -1;
            for (int i = 0; i < times.length; i++) {
                if (times[i].startsWith(selectedDate)) {
                    if (first < 0) first = i;
                    last = i;
                }
            }
            if (first < 0) {
                visibleTimes = new String[0];
                visibleValues = new double[4][];
                repaint();
                return;
            }
            String[] dayTimes = Arrays.copyOfRange(times, first, last + 1);
            double[][] dayValues = new double[values.length][];
            for (int i = 0; i < values.length; i++) {
                dayValues[i] = Arrays.copyOfRange(values[i], first, last + 1);
            }
            visibleTimes = dayTimes;
            visibleValues = dayValues;
            repaint();
        }

        private String[] visibleTimes = {};
        private double[][] visibleValues = new double[4][];

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int gap = 8;
            int cellWidth = (getWidth() - gap) / 2;
            int cellHeight = (getHeight() - gap) / 2;
            for (int i = 0; i < names.length; i++) {
                int x = (i % 2) * (cellWidth + gap);
                int y = (i / 2) * (cellHeight + gap);
                paintChart(g2, i, x, y, cellWidth, cellHeight);
            }
            g2.dispose();
        }

        private void paintChart(Graphics2D g2, int series, int x, int y, int width, int height) {
            g2.setColor(new Color(14, 17, 22, 194));
            g2.fillRoundRect(x, y, width, height, 15, 15);
            g2.setColor(new Color(255, 255, 255, 72));
            g2.drawRoundRect(x, y, width - 1, height - 1, 15, 15);

            g2.setFont(new Font("SansSerif", Font.BOLD, 11));
            g2.setColor(new Color(244, 247, 250));
            g2.drawString(names[series], x + 10, y + 15);
            double[] data = series < visibleValues.length ? visibleValues[series] : new double[0];
            int latestIndex = lastFiniteIndex(data);
            if (latestIndex >= 0) {
                String latest = String.format(Locale.ITALIAN, "%.1f", data[latestIndex]);
                g2.setFont(new Font("SansSerif", Font.BOLD, 11));
                g2.setColor(new Color(225, 231, 239));
                int labelWidth = g2.getFontMetrics().stringWidth(latest);
                g2.drawString(latest, x + width - labelWidth - 10, y + 15);
            }

            int left = x + 10;
            int top = y + 24;
            int plotWidth = width - 20;
            int plotHeight = height - 39;
            g2.setColor(new Color(220, 228, 237, 80));
            g2.drawLine(left, top + plotHeight, left + plotWidth, top + plotHeight);
            if (data.length < 2 || latestIndex < 0) {
                g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
                g2.setColor(new Color(195, 203, 213));
                g2.drawString("Dati non disponibili", left + 4, top + 17);
                return;
            }

            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (double value : data) {
                if (Double.isFinite(value)) {
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                }
            }
            double range = Math.max(1.0, max - min);
            Path2D line = new Path2D.Double();
            boolean pathStarted = false;
            for (int i = 0; i < data.length; i++) {
                if (!Double.isFinite(data[i])) {
                    pathStarted = false;
                    continue;
                }
                double pointX = left + (double) i * plotWidth / (data.length - 1);
                double pointY = top + plotHeight - ((data[i] - min) / range) * (plotHeight - 3);
                if (pathStarted) line.lineTo(pointX, pointY);
                else {
                    line.moveTo(pointX, pointY);
                    pathStarted = true;
                }
            }
            g2.setColor(new Color(208, 218, 229));
            g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(line);
            if (visibleTimes.length > 1) {
                g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
                g2.setColor(new Color(195, 203, 213));
                String first = visibleTimes[0].substring(11, 16);
                String last = visibleTimes[visibleTimes.length - 1].substring(11, 16);
                g2.drawString(first, left, y + height - 5);
                int lastWidth = g2.getFontMetrics().stringWidth(last);
                g2.drawString(last, left + plotWidth - lastWidth, y + height - 5);
            }
        }

        private int lastFiniteIndex(double[] data) {
            for (int i = data.length - 1; i >= 0; i--) {
                if (Double.isFinite(data[i])) return i;
            }
            return -1;
        }
    }

    enum RadarLayer {
        PRECIPITATION("Radar precipitazioni · live", "Precipitazioni osservate • radar RainViewer live", "", "Debole", "Intensa"),
        PRECIPITATION_FORECAST("Precipitazioni · previsione", "Precipitazioni previste • modello Open-Meteo (mm)", "precipitation", "Deboli", "Intense"),
        CLOUDS("Nuvolosità · modello", "Copertura nuvolosa • modello Open-Meteo (%)", "cloud_cover", "Sereno", "Coperto"),
        WIND("Vento · modello", "Vento a 10 m • colore = velocità, freccia = direzione", "wind_speed_10m,wind_direction_10m", "Calmo", "Forte"),
        TEMPERATURE("Temperatura · modello", "Temperatura a 2 m • blu = freddo, rosso = caldo (°C)", "temperature_2m", "Freddo", "Caldo");

        private final String title;
        private final String legend;
        private final String variables;
        private final String lowLabel;
        private final String highLabel;

        RadarLayer(String title, String legend, String variables, String lowLabel, String highLabel) {
            this.title = title;
            this.legend = legend;
            this.variables = variables;
            this.lowLabel = lowLabel;
            this.highLabel = highLabel;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private static final class RadarLoadToken {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final Set<HttpURLConnection> activeConnections = ConcurrentHashMap.newKeySet();
        private final Set<Future<?>> activeTileTasks = ConcurrentHashMap.newKeySet();

        boolean isCancelled() {
            return cancelled.get();
        }

        synchronized void register(HttpURLConnection connection) throws InterruptedException {
            check();
            activeConnections.add(connection);
        }

        synchronized void unregister(HttpURLConnection connection) {
            activeConnections.remove(connection);
        }

        synchronized void registerTileTask(Future<?> task) {
            if (cancelled.get()) {
                task.cancel(true);
            } else {
                activeTileTasks.add(task);
            }
        }

        synchronized void unregisterTileTask(Future<?> task) {
            activeTileTasks.remove(task);
        }

        synchronized void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;
            for (HttpURLConnection connection : activeConnections) {
                connection.disconnect();
            }
            activeConnections.clear();
            for (Future<?> task : activeTileTasks) {
                task.cancel(true);
            }
            activeTileTasks.clear();
            RADAR_TILE_EXECUTOR.purge();
        }

        void check() throws InterruptedException {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Caricamento mappa annullato");
            }
        }
    }

    private static void throwIfRadarRequestInterrupted(RadarLoadToken requestToken) throws InterruptedException {
        requestToken.check();
    }

    static class RadarMapPanel extends JPanel {
        private final JLabel statusLabel = createAirDataLabel("In attesa della posizione...");
        private final RadarMapCanvas mapCanvas = new RadarMapCanvas();
        private final JComboBox<RadarLayer> layerSelector = new JComboBox<>(RadarLayer.values());
        private final RadarLegendPanel legendPanel = new RadarLegendPanel();
        private double locationLatitude = Double.NaN;
        private double locationLongitude = Double.NaN;
        private int zoomLevel = 6;
        private int requestId;
        private Future<?> activeMapTask;
        private RadarLoadToken activeRadarRequest;

        RadarMapPanel() {
            setOpaque(false);
            setLayout(new BorderLayout(0, 8));

            JPanel heading = new JPanel(new BorderLayout(8, 0));
            heading.setOpaque(false);
            layerSelector.setFont(new Font("SansSerif", Font.PLAIN, 12));
            styleDropdownText(layerSelector);
            layerSelector.addActionListener(e -> {
                RadarLayer selected = (RadarLayer) layerSelector.getSelectedItem();
                if (selected != null && hasKnownStationLocation()) {
                    refresh(stationLat, stationLon, selected);
                }
            });
            JButton refreshButton = new JButton("Aggiorna");
            styleDiscreetButton(refreshButton);
            refreshButton.addActionListener(e -> refresh(stationLat, stationLon,
                    (RadarLayer) layerSelector.getSelectedItem()));
            heading.add(layerSelector, BorderLayout.CENTER);
            heading.add(refreshButton, BorderLayout.EAST);

            statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
            statusLabel.setForeground(new Color(46, 64, 80));
            JLabel navigationHint = createAirDataLabel(
                    "Mappa fissa · raggio " + RADAR_RADIUS_KM + " km dalla località cercata");
            navigationHint.setFont(new Font("SansSerif", Font.PLAIN, 11));
            navigationHint.setForeground(new Color(57, 75, 91));
            JPanel footer = new JPanel();
            footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
            footer.setOpaque(false);
            legendPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            navigationHint.setAlignmentX(Component.LEFT_ALIGNMENT);
            footer.add(legendPanel);
            footer.add(statusLabel);
            footer.add(navigationHint);
            add(heading, BorderLayout.NORTH);
            add(mapCanvas, BorderLayout.CENTER);
            add(footer, BorderLayout.SOUTH);
        }

        void refresh(double latitude, double longitude) {
            refresh(latitude, longitude, (RadarLayer) layerSelector.getSelectedItem());
        }

        void refresh(double latitude, double longitude, RadarLayer layer) {
            if (layer == null) return;
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
                statusLabel.setText("Coordinate non valide per la mappa");
                return;
            }
            if (Double.isNaN(locationLatitude)
                    || Math.abs(locationLatitude - latitude) > 0.0001
                    || Math.abs(locationLongitude - longitude) > 0.0001) {
                locationLatitude = latitude;
                locationLongitude = longitude;
                zoomLevel = calculateRadarZoom(latitude, RADAR_MAP_SIZE, RADAR_RADIUS_KM);
            }
            loadCurrentView(layer);
        }

        private void loadCurrentView() {
            loadCurrentView((RadarLayer) layerSelector.getSelectedItem());
        }

        private void loadCurrentView(RadarLayer layer) {
            if (layer == null || !hasKnownStationLocation()) return;
            ForecastDateOption dateOption = (ForecastDateOption) radarDateSelector.getSelectedItem();
            String selectedDate = dateOption == null ? LocalDate.now().toString() : dateOption.date;
            double requestLatitude = locationLatitude;
            double requestLongitude = locationLongitude;
            int requestZoom = zoomLevel;
            double markerLatitude = locationLatitude;
            double markerLongitude = locationLongitude;
            final int currentRequest;
            RadarLoadToken requestToken = new RadarLoadToken();
            synchronized (this) {
                currentRequest = ++requestId;
                if (activeRadarRequest != null) activeRadarRequest.cancel();
                if (activeMapTask != null) activeMapTask.cancel(true);
                RADAR_MAP_EXECUTOR.purge();
                RADAR_TILE_EXECUTOR.purge();
                activeRadarRequest = requestToken;
            }
            legendPanel.setLayer(layer);
            statusLabel.setText("Caricamento di " + layer.title + "...");

            Future<?> nextTask = RADAR_MAP_EXECUTOR.submit(() -> {
                try {
                    RadarMapResult result = loadRadarMap(requestLatitude, requestLongitude,
                            markerLatitude, markerLongitude, layer, selectedDate, requestZoom, requestToken);
                    SwingUtilities.invokeLater(() -> {
                        if (isCurrentRequest(currentRequest)) {
                            mapCanvas.setMapImage(result.image);
                            statusLabel.setText(result.status);
                        }
                    });
                } catch (Exception ex) {
                    if (ex instanceof InterruptedException || Thread.currentThread().isInterrupted()
                            || requestToken.isCancelled()) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    System.err.println("Caricamento radar non riuscito: " + ex.getMessage());
                    SwingUtilities.invokeLater(() -> {
                        if (isCurrentRequest(currentRequest)) {
                            statusLabel.setText("Radar temporaneamente non disponibile. Riprova tra poco.");
                        }
                    });
                }
            });
            synchronized (this) {
                if (currentRequest == requestId) activeMapTask = nextTask;
                else nextTask.cancel(true);
            }
        }

        private boolean hasKnownStationLocation() {
            return Double.isFinite(locationLatitude) && Double.isFinite(locationLongitude);
        }

        private synchronized boolean isCurrentRequest(int id) {
            return requestId == id;
        }
    }

    static class RadarLegendPanel extends JPanel {
        private RadarLayer layer = RadarLayer.PRECIPITATION;

        RadarLegendPanel() {
            setOpaque(false);
            setPreferredSize(new Dimension(440, 34));
            setMinimumSize(new Dimension(200, 34));
        }

        void setLayer(RadarLayer layer) {
            this.layer = layer;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
            g2.setColor(new Color(232, 237, 243));
            g2.drawString(layer.legend, 2, 10);

            int barX = 4;
            int barY = 17;
            int barWidth = Math.max(100, Math.min(230, getWidth() - 100));
            for (int i = 0; i < barWidth; i++) {
                double ratio = (double) i / Math.max(1, barWidth - 1);
                Color color;
                switch (layer) {
                    case PRECIPITATION:
                    case PRECIPITATION_FORECAST:
                        color = precipitationLegendColor(ratio);
                        break;
                    case CLOUDS:
                        int cloudShade = (int) Math.round(238 - ratio * 135);
                        color = new Color(cloudShade, cloudShade, Math.min(255, cloudShade + 8));
                        break;
                    case WIND:
                        color = mixColor(new Color(69, 169, 139), new Color(218, 93, 84), ratio);
                        break;
                    case TEMPERATURE:
                        color = modelLayerColor(layer, -10 + ratio * 50);
                        break;
                    default:
                        color = Color.GRAY;
                }
                g2.setColor(color);
                g2.drawLine(barX + i, barY, barX + i, barY + 7);
            }

            g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
            g2.setColor(new Color(220, 227, 235));
            g2.drawString(layer.lowLabel, barX + barWidth + 6, barY + 7);
            int highWidth = g2.getFontMetrics().stringWidth(layer.highLabel);
            g2.drawString(layer.highLabel, Math.max(barX + barWidth + 52,
                    getWidth() - highWidth - 3), barY + 7);
            g2.dispose();
        }

        private static Color precipitationLegendColor(double ratio) {
            Color[] colors = {
                    new Color(112, 211, 244), new Color(57, 151, 232),
                    new Color(73, 191, 123), new Color(238, 221, 75),
                    new Color(239, 147, 53), new Color(220, 63, 61)
            };
            double scaled = ratio * (colors.length - 1);
            int index = Math.min(colors.length - 2, (int) scaled);
            return mixColor(colors[index], colors[index + 1], scaled - index);
        }
    }

    private static RadarMapResult loadRadarMap(double latitude, double longitude,
                                               double markerLatitude, double markerLongitude,
                                               RadarLayer layer, String selectedDate, int zoom,
                                               RadarLoadToken requestToken) throws Exception {
        throwIfRadarRequestInterrupted(requestToken);
        String radarHost = "";
        String latestRadarPath = "";
        long latestRadarTime = 0;
        if (layer == RadarLayer.PRECIPITATION) {
            String radarJson = executeHttpGET("https://api.rainviewer.com/public/weather-maps.json", requestToken);
            radarHost = parseJsonString(radarJson, "host");
            latestRadarPath = parseLastJsonString(radarJson, "\"path\":\"");
            latestRadarTime = parseLastJsonLong(radarJson, "\"time\":");
            if (radarHost.isEmpty() || latestRadarPath.isEmpty()) {
                throw new IllegalStateException("Il servizio radar non ha restituito frame disponibili");
            }
        }
        final String host = radarHost;
        final String radarPath = latestRadarPath;
        final long radarFrameTime = latestRadarTime;

        int tilesPerAxis = 1 << zoom;
        double clampedLatitude = Math.max(-85.05112878, Math.min(85.05112878, latitude));
        double latitudeRadians = Math.toRadians(clampedLatitude);
        int centerX = (int) Math.floor((longitude + 180.0) / 360.0 * tilesPerAxis);
        int centerY = (int) Math.floor((1.0 - Math.log(Math.tan(latitudeRadians)
                + 1.0 / Math.cos(latitudeRadians)) / Math.PI) / 2.0 * tilesPerAxis);
        centerX = Math.floorMod(centerX, tilesPerAxis);
        centerY = Math.max(0, Math.min(tilesPerAxis - 1, centerY));
        double worldPixels = tilesPerAxis * 256.0;
        double tileScale = radarTileScale(latitude, zoom, RADAR_MAP_SIZE, RADAR_RADIUS_KM);
        double centerWorldX = (longitude + 180.0) / 360.0 * worldPixels;
        double centerWorldY = (1.0 - Math.log(Math.tan(latitudeRadians)
                + 1.0 / Math.cos(latitudeRadians)) / Math.PI) / 2.0 * worldPixels;
        double fractionX = centerWorldX - Math.floor(centerWorldX / 256.0) * 256.0;
        double fractionY = centerWorldY - Math.floor(centerWorldY / 256.0) * 256.0;
        double halfMapWorldPixels = RADAR_MAP_SIZE / (2.0 * tileScale);
        int firstTileOffsetX = (int) Math.floor((fractionX - halfMapWorldPixels) / 256.0);
        int lastTileOffsetX = (int) Math.ceil((fractionX + halfMapWorldPixels) / 256.0) - 1;
        int firstTileOffsetY = (int) Math.floor((fractionY - halfMapWorldPixels) / 256.0);
        int lastTileOffsetY = (int) Math.ceil((fractionY + halfMapWorldPixels) / 256.0) - 1;
        java.util.List<Future<RadarTile>> tileRequests = new java.util.ArrayList<>();
        for (int tileOffsetY = firstTileOffsetY; tileOffsetY <= lastTileOffsetY; tileOffsetY++) {
            for (int tileOffsetX = firstTileOffsetX; tileOffsetX <= lastTileOffsetX; tileOffsetX++) {
                final int dx = tileOffsetX;
                final int dy = tileOffsetY;
                final int tileX = Math.floorMod(centerX + dx, tilesPerAxis);
                final int tileY = Math.max(0, Math.min(tilesPerAxis - 1, centerY + dy));
                Future<RadarTile> tileRequest = RADAR_TILE_EXECUTOR.submit(() -> {
                    throwIfRadarRequestInterrupted(requestToken);
                    BufferedImage base = null;
                    BufferedImage radar = null;
                    try {
                        base = readCachedTile("https://tile.openstreetmap.org/" + zoom + "/" + tileX + "/" + tileY + ".png",
                                true, requestToken);
                    } catch (Exception ex) {
                        if (ex instanceof InterruptedException || Thread.currentThread().isInterrupted()
                                || requestToken.isCancelled()) {
                            throw new InterruptedException("Caricamento tile annullato");
                        }
                        System.err.println("Tile cartografica non disponibile: " + ex.getMessage());
                    }
                    throwIfRadarRequestInterrupted(requestToken);
                    try {
                        if (layer == RadarLayer.PRECIPITATION) {
                            radar = readCachedTile(host + radarPath + "/256/" + zoom + "/" + tileX + "/" + tileY
                                    + "/2/1_1.png", false, requestToken);
                        }
                    } catch (Exception ex) {
                        if (ex instanceof InterruptedException || Thread.currentThread().isInterrupted()
                                || requestToken.isCancelled()) {
                            throw new InterruptedException("Caricamento tile annullato");
                        }
                        System.err.println("Tile precipitazioni non disponibile: " + ex.getMessage());
                    }
                    throwIfRadarRequestInterrupted(requestToken);
                    return new RadarTile(dx, dy, base, radar);
                });
                tileRequests.add(tileRequest);
                requestToken.registerTileTask(tileRequest);
            }
        }

        BufferedImage map = new BufferedImage(RADAR_MAP_SIZE, RADAR_MAP_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = map.createGraphics();
        g2.setColor(new Color(231, 236, 240));
        g2.fillRect(0, 0, map.getWidth(), map.getHeight());
        int radarTileCount = 0;
        int baseMapTileCount = 0;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            for (Future<RadarTile> future : tileRequests) {
                requestToken.check();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try {
                    RadarTile tile = future.get(remaining, TimeUnit.NANOSECONDS);
                    requestToken.unregisterTileTask(future);
                    int x = (int) Math.round(RADAR_MAP_SIZE / 2.0
                            + (tile.offsetX * 256 - fractionX) * tileScale);
                    int y = (int) Math.round(RADAR_MAP_SIZE / 2.0
                            + (tile.offsetY * 256 - fractionY) * tileScale);
                    int tileSize = (int) Math.round(256 * tileScale);
                    if (tile.baseMap != null) {
                        g2.drawImage(tile.baseMap, x, y, tileSize, tileSize, null);
                        baseMapTileCount++;
                    }
                    if (tile.radar != null) {
                        g2.drawImage(tile.radar, x, y, tileSize, tileSize, null);
                        radarTileCount++;
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw ex;
                } catch (TimeoutException ex) {
                    break;
                } catch (Exception ex) {
                    requestToken.unregisterTileTask(future);
                    System.err.println("Una tile radar non è stata caricata: " + ex.getMessage());
                }
            }
        } finally {
            for (Future<RadarTile> request : tileRequests) {
                requestToken.unregisterTileTask(request);
                if (!request.isDone()) request.cancel(true);
            }
            RADAR_TILE_EXECUTOR.purge();
            g2.dispose();
        }

        if (layer == RadarLayer.PRECIPITATION && radarTileCount == 0) {
            throw new IllegalStateException("Nessuna tile radar disponibile per questa zona");
        }
        if (baseMapTileCount == 0) {
            throw new IllegalStateException("Cartografia di base non disponibile");
        }
        if (layer != RadarLayer.PRECIPITATION) {
            throwIfRadarRequestInterrupted(requestToken);
            addModelLayer(map, latitude, longitude, centerWorldX, centerWorldY, zoom, layer,
                    selectedDate, tileScale, requestToken);
        }
        throwIfRadarRequestInterrupted(requestToken);

        double safeMarkerLatitude = Math.max(-85.05112878, Math.min(85.05112878, markerLatitude));
        double markerWorldX = (markerLongitude + 180.0) / 360.0 * worldPixels;
        double horizontalDelta = markerWorldX - centerWorldX;
        if (horizontalDelta > worldPixels / 2) horizontalDelta -= worldPixels;
        if (horizontalDelta < -worldPixels / 2) horizontalDelta += worldPixels;
        double markerWorldY = (1.0 - Math.log(Math.tan(Math.toRadians(safeMarkerLatitude))
                + 1.0 / Math.cos(Math.toRadians(safeMarkerLatitude))) / Math.PI) / 2.0 * worldPixels;
        int markerX = (int) Math.round(RADAR_MAP_SIZE / 2.0 + horizontalDelta * tileScale);
        int markerY = (int) Math.round(RADAR_MAP_SIZE / 2.0
                + (markerWorldY - centerWorldY) * tileScale);
        g2 = map.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (markerX >= 0 && markerX < map.getWidth() && markerY >= 0 && markerY < map.getHeight()) {
            g2.setColor(new Color(255, 255, 255, 220));
            g2.fillOval(markerX - 9, markerY - 9, 18, 18);
            g2.setColor(new Color(47, 112, 160));
            g2.fillOval(markerX - 6, markerY - 6, 12, 12);
        }
        g2.dispose();

        String status = layer == RadarLayer.PRECIPITATION
                ? "Radar live aggiornato alle " + formatRadarTime(radarFrameTime)
                    + " · raggio " + RADAR_RADIUS_KM + " km"
                : "Previsione " + selectedDate + " alle 12:00"
                    + " · raggio " + RADAR_RADIUS_KM + " km";
        if (baseMapTileCount < tileRequests.size()) {
            System.err.println("Cartografia parziale: caricate " + baseMapTileCount
                    + " di " + tileRequests.size() + " tile.");
        }
        return new RadarMapResult(map, status);
    }

    static int calculateRadarZoom(double latitude, int mapSize, int radiusKm) {
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
                || mapSize < 1 || radiusKm < 1) {
            throw new IllegalArgumentException("Dimensioni o coordinate radar non valide");
        }
        double clampedLatitude = Math.max(-85.05112878, Math.min(85.05112878, latitude));
        double metersPerPixelAtZoomZero = EARTH_CIRCUMFERENCE_METERS
                * Math.cos(Math.toRadians(clampedLatitude)) / 256.0;
        double targetWorldPixelSpan = 2.0 * radiusKm * 1000.0
                / metersPerPixelAtZoomZero;
        int zoom = (int) Math.round(Math.log(mapSize / targetWorldPixelSpan) / Math.log(2.0));
        return Math.max(3, Math.min(10, zoom));
    }

    static double radarTileScale(double latitude, int zoom, int mapSize, int radiusKm) {
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
                || zoom < 0 || zoom > 20 || mapSize < 1 || radiusKm < 1) {
            throw new IllegalArgumentException("Dimensioni o coordinate radar non valide");
        }
        double clampedLatitude = Math.max(-85.05112878, Math.min(85.05112878, latitude));
        double metersPerPixel = EARTH_CIRCUMFERENCE_METERS
                * Math.cos(Math.toRadians(clampedLatitude)) / (256.0 * (1 << zoom));
        double targetSpanInTilePixels = 2.0 * radiusKm * 1000.0 / metersPerPixel;
        return mapSize / targetSpanInTilePixels;
    }

    private static void addModelLayer(BufferedImage map, double latitude, double longitude,
                                     double centerWorldX, double centerWorldY, int zoom, RadarLayer layer,
                                     String selectedDate, double tileScale,
                                     RadarLoadToken requestToken) throws Exception {
        requestToken.check();
        int gridSize = 4;
        int worldTileCount = 1 << zoom;
        double worldPixels = worldTileCount * 256.0;
        StringBuilder latitudes = new StringBuilder();
        StringBuilder longitudes = new StringBuilder();
        int[] sampleX = new int[gridSize * gridSize];
        int[] sampleY = new int[gridSize * gridSize];

        for (int row = 0; row < gridSize; row++) {
            for (int column = 0; column < gridSize; column++) {
                int index = row * gridSize + column;
                int x = 48 + column * 138;
                int y = 48 + row * 138;
                sampleX[index] = x;
                sampleY[index] = y;
                double worldX = centerWorldX + (x - RADAR_MAP_SIZE / 2.0) / tileScale;
                double worldY = centerWorldY + (y - RADAR_MAP_SIZE / 2.0) / tileScale;
                double sampleLongitude = ((worldX / worldPixels * 360.0 - 180.0 + 540.0) % 360.0) - 180.0;
                double mercator = Math.PI * (1.0 - 2.0 * worldY / worldPixels);
                double sampleLatitude = Math.toDegrees(Math.atan(Math.sinh(mercator)));
                if (latitudes.length() > 0) {
                    latitudes.append(',');
                    longitudes.append(',');
                }
                latitudes.append(String.format(Locale.US, "%.5f", sampleLatitude));
                longitudes.append(String.format(Locale.US, "%.5f", sampleLongitude));
            }
        }

        String endpoint = "https://api.open-meteo.com/v1/forecast?latitude=" + latitudes
                + "&longitude=" + longitudes
                + "&hourly=" + layer.variables
                + "&forecast_days=7"
                + "&timezone=auto";
        String response = executeHttpGET(endpoint, requestToken);
        requestToken.check();
        java.util.List<String> locations = parseTopLevelJsonObjects(response);
        if (locations.size() != gridSize * gridSize) {
            throw new IllegalStateException("Il modello meteo non ha restituito tutti i punti della mappa");
        }

        double[] values = new double[locations.size()];
        double[] directions = new double[locations.size()];
        Arrays.fill(directions, Double.NaN);
        for (int i = 0; i < locations.size(); i++) {
            int hourlyIndex = locations.get(i).indexOf("\"hourly\":");
            if (hourlyIndex < 0) throw new IllegalStateException("Dati modello incompleti");
            String hourly = locations.get(i).substring(hourlyIndex);
                    String[] times = parseJsonStringArray(hourly, "\"time\":[");
            int selectedHour = findForecastHour(times, selectedDate);
            switch (layer) {
                case PRECIPITATION_FORECAST:
                    values[i] = parseNullableJsonDoubleArray(hourly, "\"precipitation\":[")[selectedHour];
                    break;
                case CLOUDS:
                    values[i] = parseNullableJsonDoubleArray(hourly, "\"cloud_cover\":[")[selectedHour];
                    break;
                case TEMPERATURE:
                    values[i] = parseNullableJsonDoubleArray(hourly, "\"temperature_2m\":[")[selectedHour];
                    break;
                case WIND:
                    values[i] = parseNullableJsonDoubleArray(hourly, "\"wind_speed_10m\":[")[selectedHour];
                    directions[i] = parseNullableJsonDoubleArray(hourly, "\"wind_direction_10m\":[")[selectedHour];
                    break;
                case PRECIPITATION:
                default:
                    throw new IllegalArgumentException("Tipo di mappa modello non supportato");
            }
            if (!Double.isFinite(values[i])) {
                throw new IllegalStateException("Dato modello non disponibile per " + selectedDate);
            }
        }

        Graphics2D g2 = map.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (int i = 0; i < values.length; i++) {
            Color color = modelLayerColor(layer, values[i]);
            int radius = 74;
            g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 94));
            g2.fillOval(sampleX[i] - radius, sampleY[i] - radius, radius * 2, radius * 2);
            if (layer == RadarLayer.WIND) {
                drawWindArrow(g2, sampleX[i], sampleY[i], directions[i], values[i]);
            }
        }
        g2.dispose();
    }

    private static Color modelLayerColor(RadarLayer layer, double value) {
        if (layer == RadarLayer.PRECIPITATION_FORECAST) {
            double ratio = Math.max(0, Math.min(1, value / 10.0));
            return RadarLegendPanel.precipitationLegendColor(ratio);
        }
        if (layer == RadarLayer.CLOUDS) {
            int shade = (int) Math.round(240 - Math.max(0, Math.min(100, value)) * 1.25);
            return new Color(shade, shade, Math.min(255, shade + 8));
        }
        if (layer == RadarLayer.WIND) {
            if (value < 10) return new Color(69, 169, 139);
            if (value < 25) return new Color(236, 191, 82);
            return new Color(218, 93, 84);
        }
        double ratio = Math.max(0, Math.min(1, (value + 10) / 50));
        if (ratio < 0.5) {
            return mixColor(new Color(53, 119, 190), new Color(236, 218, 133), ratio * 2);
        }
        return mixColor(new Color(236, 218, 133), new Color(203, 77, 68), (ratio - 0.5) * 2);
    }

    private static int findForecastHour(String[] times, String date) {
        int bestIndex = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < times.length; i++) {
            if (!times[i].startsWith(date + "T") || times[i].length() < 13) continue;
            int hour = Integer.parseInt(times[i].substring(11, 13));
            int distance = Math.abs(hour - 12);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }
        if (bestIndex < 0) {
            throw new IllegalStateException("Nessuna previsione oraria disponibile per " + date);
        }
        return bestIndex;
    }

    private static Color mixColor(Color first, Color second, double ratio) {
        double amount = Math.max(0, Math.min(1, ratio));
        return new Color(
                (int) Math.round(first.getRed() + (second.getRed() - first.getRed()) * amount),
                (int) Math.round(first.getGreen() + (second.getGreen() - first.getGreen()) * amount),
                (int) Math.round(first.getBlue() + (second.getBlue() - first.getBlue()) * amount));
    }

    private static void drawWindArrow(Graphics2D graphics, int x, int y, double direction, double speed) {
        double angle = Math.toRadians(direction + 180);
        int length = (int) Math.min(34, 15 + speed);
        int endX = x + (int) Math.round(Math.sin(angle) * length);
        int endY = y - (int) Math.round(Math.cos(angle) * length);
        graphics.setColor(new Color(49, 69, 77, 220));
        graphics.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.drawLine(x, y, endX, endY);
        double wingAngle = Math.PI / 6;
        for (int sign : new int[]{-1, 1}) {
            double wing = angle + Math.PI + sign * wingAngle;
            graphics.drawLine(endX, endY,
                    endX + (int) Math.round(Math.sin(wing) * 8),
                    endY - (int) Math.round(Math.cos(wing) * 8));
        }
    }

    private static java.util.List<String> parseTopLevelJsonObjects(String json) {
        java.util.List<String> objects = new java.util.ArrayList<>();
        int depth = 0;
        int objectStart = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char character = json.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') inString = false;
                continue;
            }
            if (character == '"') {
                inString = true;
            } else if (character == '{') {
                if (depth == 0) objectStart = i;
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0 && objectStart >= 0) {
                    objects.add(json.substring(objectStart, i + 1));
                    objectStart = -1;
                }
            }
        }
        return objects;
    }

    private static BufferedImage readTile(String address, boolean mapTile,
                                          RadarLoadToken requestToken) throws Exception {
        requestToken.check();
        HttpsURLConnection connection = openSecureConnection(address);
        requestToken.register(connection);
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(3500);
        connection.setReadTimeout(3500);
        if (mapTile) {
            connection.setRequestProperty("User-Agent", "MeteoBetti/1.0 (personal weather application)");
        }
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new java.io.IOException("Risposta HTTP " + responseCode
                        + " dal servizio cartografico " + connection.getURL().getHost());
            }
            byte[] imageBytes;
            try (java.io.InputStream input = connection.getInputStream()) {
                imageBytes = input.readNBytes(MAX_TILE_RESPONSE_BYTES + 1);
            }
            if (imageBytes.length > MAX_TILE_RESPONSE_BYTES) {
                throw new java.io.IOException("La tile cartografica supera la dimensione massima consentita");
            }
            BufferedImage image = decodeTileImage(imageBytes);
            requestToken.check();
            if (image == null) throw new IllegalStateException("Immagine tile non valida");
            return image;
        } finally {
            requestToken.unregister(connection);
            connection.disconnect();
        }
    }

    private static BufferedImage readCachedTile(String address, boolean mapTile,
                                                RadarLoadToken requestToken) throws Exception {
        requestToken.check();
        synchronized (RADAR_TILE_CACHE) {
            BufferedImage cached = RADAR_TILE_CACHE.get(address);
            if (cached != null) return cached;
        }
        BufferedImage loaded = readTile(address, mapTile, requestToken);
        requestToken.check();
        synchronized (RADAR_TILE_CACHE) {
            BufferedImage cached = RADAR_TILE_CACHE.get(address);
            if (cached != null) return cached;
            RADAR_TILE_CACHE.put(address, loaded);
        }
        return loaded;
    }

    private static String parseLastJsonString(String json, String marker) {
        int start = 0;
        String lastValue = "";
        while ((start = json.indexOf(marker, start)) >= 0) {
            start += marker.length();
            int end = json.indexOf('"', start);
            if (end < 0) break;
            lastValue = json.substring(start, end);
            start = end + 1;
        }
        return lastValue;
    }

    private static long parseLastJsonLong(String json, String marker) {
        int start = 0;
        long lastValue = 0;
        while ((start = json.indexOf(marker, start)) >= 0) {
            start += marker.length();
            int end = start;
            while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
            if (end > start) lastValue = Long.parseLong(json.substring(start, end));
            start = end;
        }
        return lastValue;
    }

    private static String formatRadarTime(long epochSeconds) {
        if (epochSeconds <= 0) return "• orario n/d";
        String time = DateTimeFormatter.ofPattern("HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochSecond(epochSeconds));
        return "• " + time;
    }

    private static final class RadarTile {
        private final int offsetX;
        private final int offsetY;
        private final BufferedImage baseMap;
        private final BufferedImage radar;

        private RadarTile(int offsetX, int offsetY, BufferedImage baseMap,
                          BufferedImage radar) {
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.baseMap = baseMap;
            this.radar = radar;
        }
    }

    private static final class RadarMapResult {
        private final BufferedImage image;
        private final String status;

        private RadarMapResult(BufferedImage image, String status) {
            this.image = image;
            this.status = status;
        }
    }

    private static final class RadarMapCanvas extends JPanel {
        private BufferedImage mapImage;

        private RadarMapCanvas() {
            setOpaque(true);
            setDoubleBuffered(true);
            setBackground(new Color(22, 27, 34));
            setPreferredSize(new Dimension(460, 390));
        }

        private void setMapImage(BufferedImage mapImage) {
            this.mapImage = mapImage;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int inset = 2;
            int width = getWidth() - inset * 2;
            int height = getHeight() - inset * 2;
            g2.setColor(new Color(232, 237, 241));
            g2.fillRoundRect(inset, inset, width, height, 18, 18);
            if (mapImage == null) {
                g2.setColor(new Color(205, 213, 223));
                g2.setFont(new Font("SansSerif", Font.PLAIN, 13));
                String message = "La mappa radar apparirà dopo il caricamento della posizione";
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(message, Math.max(12, (getWidth() - fm.stringWidth(message)) / 2),
                        getHeight() / 2);
            } else {
                double scale = Math.min((double) width / mapImage.getWidth(),
                        (double) height / mapImage.getHeight());
                int drawWidth = (int) Math.round(mapImage.getWidth() * scale);
                int drawHeight = (int) Math.round(mapImage.getHeight() * scale);
                int x = (getWidth() - drawWidth) / 2;
                int y = (getHeight() - drawHeight) / 2;
                g2.clip(new java.awt.geom.RoundRectangle2D.Float(inset, inset, width, height, 18, 18));
                g2.drawImage(mapImage, x, y, drawWidth, drawHeight, null);
            }
            g2.setColor(new Color(26, 34, 44, 170));
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawRoundRect(inset, inset, width - 1, height - 1, 18, 18);
            g2.dispose();
        }
    }

    private static JLabel createSectionTitle(String text) {
        JLabel title = new JLabel(text);
        title.setFont(uiFont(Font.BOLD, 20f));
        title.setForeground(new Color(247, 249, 252));
        return title;
    }

    static class AdvancedThermalGraphPanel extends JPanel {
        private double[] graphValues = {};
        private String[] graphLabels = {};

        public AdvancedThermalGraphPanel() {
            setOpaque(false);
        }

        public void refreshDataPoints(String[] times, double[] temperatures) {
            graphLabels = times.clone();
            graphValues = temperatures.clone();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            paintFrostedCard(g2, getWidth(), getHeight(), 26);

            g2.setColor(new Color(242, 245, 249));
            g2.setFont(uiFont(Font.BOLD, 13f));
            g2.drawString("Temperatura · prossime ore", 18, 26);

            if (graphValues.length < 2) {
            g2.setColor(new Color(197, 205, 215));
            g2.setFont(uiFont(Font.PLAIN, 13f));
            g2.drawString("In attesa delle previsioni orarie...", 18, 62);
                g2.dispose();
                return;
            }

            int pX = 35;
            int pY = 48;
            int w = getWidth() - (pX * 2);
            int h = getHeight() - pY - 40;

            Path2D path = new Path2D.Double();
            Path2D fill = new Path2D.Double();
            double min = graphValues[0], max = graphValues[0];
            for (double v : graphValues) {
                if (v < min) min = v;
                if (v > max) max = v;
            }
            min -= 2;
            max += 2;

            double stepX = graphValues.length == 1 ? 0 : (double) w / (graphValues.length - 1);
            double[] pointX = new double[graphValues.length];
            double[] pointY = new double[graphValues.length];
            for (int i = 0; i < graphValues.length; i++) {
                double val = graphValues[i];
                double x = pX + (i * stepX);
                double norm = (val - min) / (max - min == 0 ? 1 : max - min);
                double y = pY + h - (norm * h);
                pointX[i] = x;
                pointY[i] = y;
            }
            path.moveTo(pointX[0], pointY[0]);
            fill.moveTo(pointX[0], pY + h);
            fill.lineTo(pointX[0], pointY[0]);
            for (int i = 1; i < pointX.length; i++) {
                double midX = (pointX[i - 1] + pointX[i]) / 2;
                double midY = (pointY[i - 1] + pointY[i]) / 2;
                path.quadTo(pointX[i - 1], pointY[i - 1], midX, midY);
                fill.quadTo(pointX[i - 1], pointY[i - 1], midX, midY);
            }
            path.lineTo(pointX[pointX.length - 1], pointY[pointY.length - 1]);
            fill.lineTo(pointX[pointX.length - 1], pointY[pointY.length - 1]);
            fill.lineTo(pointX[pointX.length - 1], pY + h);
            fill.lineTo(pointX[0], pY + h);
            fill.closePath();

            g2.setPaint(new GradientPaint(0, pY, new Color(72, 148, 214, 90),
                    0, pY + h, new Color(72, 148, 214, 8)));
            g2.fill(fill);
            g2.setColor(new Color(54, 132, 196));
            g2.setStroke(new BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(path);

            for (int i = 0; i < graphValues.length; i++) {
                double val = graphValues[i];
                double x = pointX[i];
                double y = pointY[i];
                g2.setColor(new Color(244, 247, 250));
                g2.setFont(uiFont(Font.BOLD, 12f));
                String valueLabel = Math.round(val) + "°";
                g2.drawString(valueLabel, (int) x - g2.getFontMetrics().stringWidth(valueLabel) / 2,
                        (int) y - 12);
                if (i < graphLabels.length) {
                    String timeLabel = graphLabels[i].substring(11, 16);
                    g2.setFont(uiFont(Font.PLAIN, 11f));
                    int labelWidth = g2.getFontMetrics().stringWidth(timeLabel);
                    g2.drawString(timeLabel, (int) x - labelWidth / 2, getHeight() - 14);
                }
            }
            g2.dispose();
        }
    }

    private static void initPreciseLocationEngine(JFrame parentFrame) {
        new Thread(() -> {
            LocationFix locationFix = null;
            try {
                locationFix = readSystemLocation();
            } catch (Exception e) {
                System.err.println("Posizione precisa non disponibile: " + e.getMessage());
            }

            if (locationFix != null) {
                LocationFix preciseLocation = locationFix;
                SwingUtilities.invokeLater(() ->
                        mainLocationDisplayLabel.setText("Ricerca meteo per la tua posizione..."));
                executeAsyncWeatherFetch(parentFrame, preciseLocation);
                return;
            }

            SwingUtilities.invokeLater(() ->
                    mainLocationDisplayLabel.setText("Posizione non disponibile: cerca una città."));
        }).start();
    }

    private static LocationFix readSystemLocation() throws Exception {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("mac")) return readMacLocation();
        if (osName.startsWith("windows")) return readWindowsLocation();
        if (osName.contains("linux")) return readLinuxLocation();
        return null;
    }

    private static LocationFix readMacLocation() throws Exception {
        Path appBundle = null;
        try {
            Path codePath = Paths.get(MeteoBettiUltimateOS.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath();
            for (Path path = codePath; path != null; path = path.getParent()) {
                Path fileName = path.getFileName();
                if (fileName != null && fileName.toString().endsWith(".app")) {
                    appBundle = path;
                    break;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Impossibile individuare il bundle macOS", e);
        }

        if (appBundle == null) {
            return null;
        }

        Path helper = appBundle.resolve("Contents/Helpers/LocationAgent.app/Contents/MacOS/LocationAgent");
        if (!java.nio.file.Files.isExecutable(helper)) {
            throw new IllegalStateException("Helper di localizzazione assente: " + helper);
        }

        Process process = new ProcessBuilder(helper.toString()).start();
        if (!process.waitFor(35, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Richiesta di posizione scaduta");
        }

        String output;
        String errorOutput;
        try (BufferedReader stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
             BufferedReader stderr = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            output = stdout.readLine();
            errorOutput = stderr.readLine();
        }

        if (process.exitValue() != 0 || output == null) {
            throw new IllegalStateException(errorOutput == null
                    ? "Autorizzazione negata o posizione non disponibile"
                    : errorOutput);
        }

        String[] values = output.split("\\|", 4);
        if (values.length < 2) {
            throw new IllegalStateException("Risposta non valida dall'helper di localizzazione");
        }

        double latitude = Double.parseDouble(values[0]);
        double longitude = Double.parseDouble(values[1]);
        String city = values.length > 2 ? values[2] : "";
        String country = values.length > 3 ? values[3] : "";
        return new LocationFix(latitude, longitude, city, country);
    }

    private static LocationFix readWindowsLocation() throws Exception {
        String script = "$ErrorActionPreference = 'Stop'; "
                + "Add-Type -AssemblyName System.Device; "
                + "$watcher = New-Object -TypeName System.Device.Location.GeoCoordinateWatcher "
                + "-ArgumentList ([System.Device.Location.GeoPositionAccuracy]::High); "
                + "$watcher.Start(); "
                + "$deadline = [DateTime]::UtcNow.AddSeconds(25); "
                + "while ($watcher.Status -ne 'Ready' -and [DateTime]::UtcNow -lt $deadline) { "
                + "Start-Sleep -Milliseconds 250 }; "
                + "$location = $watcher.Position.Location; "
                + "if ($watcher.Status -ne 'Ready' -or $location.IsUnknown) { "
                + "throw 'Posizione Windows non disponibile o permesso negato'; }; "
                + "$culture = [Globalization.CultureInfo]::InvariantCulture; "
                + "[Console]::Out.WriteLine([String]::Format($culture, '{0:R}|{1:R}', "
                + "$location.Latitude, $location.Longitude)); "
                + "$watcher.Stop()";
        String output = runLocationCommand(java.util.Arrays.asList(
                "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                "-Command", script), 35);
        String[] coordinates = output.trim().split("\\|", 2);
        if (coordinates.length != 2) {
            throw new IllegalStateException("Risposta non valida dal servizio posizione di Windows");
        }
        return new LocationFix(Double.parseDouble(coordinates[0]),
                Double.parseDouble(coordinates[1]), "", "");
    }

    private static LocationFix readLinuxLocation() throws Exception {
        String manager = "/org/freedesktop/GeoClue2/Manager";
        String clientResult = runLocationCommand(java.util.Arrays.asList(
                "gdbus", "call", "--system", "--dest", "org.freedesktop.GeoClue2",
                "--object-path", manager, "--method",
                "org.freedesktop.GeoClue2.Manager.GetClient"), 10);
        java.util.regex.Matcher clientMatch = java.util.regex.Pattern
                .compile("objectpath '([^']+)'").matcher(clientResult);
        if (!clientMatch.find()) {
            throw new IllegalStateException("GeoClue non ha restituito un client valido");
        }

        String clientPath = clientMatch.group(1);
        boolean started = false;
        try {
            runLocationCommand(java.util.Arrays.asList(
                    "gdbus", "call", "--system", "--dest", "org.freedesktop.GeoClue2",
                    "--object-path", clientPath, "--method", "org.freedesktop.DBus.Properties.Set",
                    "org.freedesktop.GeoClue2.Client", "DesktopId", "'it.meteobetti.MeteoBetti'"), 10);
            runLocationCommand(java.util.Arrays.asList(
                    "gdbus", "call", "--system", "--dest", "org.freedesktop.GeoClue2",
                    "--object-path", clientPath, "--method", "org.freedesktop.DBus.Properties.Set",
                    "org.freedesktop.GeoClue2.Client", "RequestedAccuracyLevel", "uint32 8"), 10);
            runLocationCommand(java.util.Arrays.asList(
                    "gdbus", "call", "--system", "--dest", "org.freedesktop.GeoClue2",
                    "--object-path", clientPath, "--method", "org.freedesktop.GeoClue2.Client.Start"), 10);
            started = true;

            String locationResult = runLocationCommand(java.util.Arrays.asList(
                    "gdbus", "call", "--system", "--dest", "org.freedesktop.GeoClue2",
                    "--object-path", clientPath, "--method", "org.freedesktop.DBus.Properties.Get",
                    "org.freedesktop.GeoClue2.Client", "Location"), 30);
            java.util.regex.Matcher locationMatch = java.util.regex.Pattern
                    .compile("objectpath '([^']+)'").matcher(locationResult);
            if (!locationMatch.find()) {
                throw new IllegalStateException("GeoClue non ha restituito una posizione");
            }
            String locationPath = locationMatch.group(1);
            double latitude = readGeoClueCoordinate(locationPath, "Latitude");
            double longitude = readGeoClueCoordinate(locationPath, "Longitude");
            return new LocationFix(latitude, longitude, "", "");
        } finally {
            if (started) {
                try {
                    runLocationCommand(java.util.Arrays.asList(
                            "gdbus", "call", "--system", "--dest", "org.freedesktop.GeoClue2",
                            "--object-path", clientPath, "--method",
                            "org.freedesktop.GeoClue2.Client.Stop"), 10);
                } catch (Exception e) {
                    System.err.println("Chiusura della sessione GeoClue non riuscita: " + e.getMessage());
                }
            }
        }
    }

    private static double readGeoClueCoordinate(String locationPath, String property) throws Exception {
        String result = runLocationCommand(java.util.Arrays.asList(
                "gdbus", "call", "--system", "--dest", "org.freedesktop.GeoClue2",
                "--object-path", locationPath, "--method", "org.freedesktop.DBus.Properties.Get",
                "org.freedesktop.GeoClue2.Location", property), 10);
        java.util.regex.Matcher numberMatch = java.util.regex.Pattern
                .compile("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?")
                .matcher(result);
        if (!numberMatch.find()) {
            throw new IllegalStateException("GeoClue non ha restituito la coordinata " + property);
        }
        return Double.parseDouble(numberMatch.group());
    }

    private static String runLocationCommand(java.util.List<String> command, int timeoutSeconds)
            throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Richiesta al servizio di posizione scaduta");
        }
        String output;
        try (java.io.InputStream stream = process.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(output.isEmpty()
                    ? "Servizio di posizione non disponibile" : output);
        }
        return output;
    }

    private static final class LocationFix {
        private final double latitude;
        private final double longitude;
        private final String city;
        private final String country;

        private LocationFix(double latitude, double longitude, String city, String country) {
            if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
                    || !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
                throw new IllegalArgumentException("Coordinate geografiche non valide");
            }
            this.latitude = latitude;
            this.longitude = longitude;
            this.city = city == null ? "" : city;
            this.country = country == null ? "" : country;
        }
    }

    private static void loadAppWallpaper(String iconStr) {
        String fileName = "sfondo.jpg";
        if (iconStr.contains("🌧️") || iconStr.contains("⛈️")) {
            fileName = "sfondo_pioggia.jpg";
        } else if (iconStr.contains("🌤️") || iconStr.contains("☀️")) {
            fileName = "sfondo_sole.jpg";
        } else if (iconStr.contains("☁️") || iconStr.contains("🌫️")) {
            fileName = "sfondo_nuvoloso.jpg";
        }

        File f = new File(fileName);
        if (!f.exists()) f = new File("sfondo.jpg");
        if (!f.exists()) f = new File("sfondo.jpeg");

        try {
            if (f.exists()) {
                currentWallpaperImage = ImageIO.read(f);
            } else {
                URL wallpaperResource = MeteoBettiUltimateOS.class.getResource("/" + fileName);
                currentWallpaperImage = wallpaperResource == null ? null : ImageIO.read(wallpaperResource);
            }
        } catch (Exception ex) {
            System.err.println("Impossibile caricare lo sfondo " + fileName + ": " + ex.getMessage());
            currentWallpaperImage = null;
        }
    }

    private static void executeAsyncWeatherFetch(JFrame parentFrame, String queryLocation) {
        new Thread(() -> {
            try {
                String searchQuery = queryLocation.toLowerCase().contains("italy") || queryLocation.toLowerCase().contains("italia")
                        ? queryLocation : queryLocation + ", Italy";
                String encoded = URLEncoder.encode(searchQuery, StandardCharsets.UTF_8.name());
                String geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=" + encoded + "&count=1&language=it&format=json";
                String geoJson = executeHttpGET(geoUrl);

                if (!geoJson.contains("\"results\":")) {
                    encoded = URLEncoder.encode(queryLocation, StandardCharsets.UTF_8.name());
                    geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=" + encoded + "&count=1&language=it&format=json";
                    geoJson = executeHttpGET(geoUrl);

                    if (!geoJson.contains("\"results\":")) {
                        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                                parentFrame, "❌ Località non trovata nei server Open-Meteo!",
                                "Errore Ricerca", JOptionPane.ERROR_MESSAGE));
                        return;
                    }
                }

                double lat = parseJsonNumber(geoJson, "\"latitude\":");
                double lon = parseJsonNumber(geoJson, "\"longitude\":");
                String name = parseJsonString(geoJson, "name");
                String country = parseJsonString(geoJson, "country");
                fetchWeatherAndUpdate(parentFrame, lat, lon, name, country);

            } catch (Exception ex) {
                System.err.println("Download meteo non riuscito: " + ex.getMessage());
                showWeatherFetchError(parentFrame, "Impossibile scaricare i dati meteo.", ex);
            }
        }).start();
    }

    private static void executeAsyncWeatherFetch(JFrame parentFrame, LocationFix locationFix) {
        new Thread(() -> {
            try {
                String city = locationFix.city.isEmpty() ? "Posizione attuale" : locationFix.city;
                String country = locationFix.country.isEmpty() ? "" : locationFix.country;
                fetchWeatherAndUpdate(parentFrame, locationFix.latitude, locationFix.longitude, city, country);
            } catch (Exception ex) {
                System.err.println("Download meteo per posizione non riuscito: " + ex.getMessage());
                showWeatherFetchError(parentFrame,
                        "Impossibile scaricare i dati meteo per la posizione rilevata.", ex);
            }
        }).start();
    }

    private static void showWeatherFetchError(JFrame parentFrame, String message, Exception error) {
        String details = error.getMessage();
        if (details == null || details.isBlank()) details = error.getClass().getSimpleName();
        if (details.length() > 240) details = details.substring(0, 240) + "...";
        String finalDetails = details;
        SwingUtilities.invokeLater(() -> {
            if (mainLocationDisplayLabel != null) {
                mainLocationDisplayLabel.setText("Dati meteo non disponibili");
            }
            JOptionPane.showMessageDialog(parentFrame,
                    message + "\n" + finalDetails, "Errore di Rete", JOptionPane.ERROR_MESSAGE);
        });
    }

    private static void fetchWeatherAndUpdate(JFrame parentFrame, double lat, double lon,
                                             String name, String country) throws Exception {
        if (!Double.isFinite(lat) || lat < -90 || lat > 90
                || !Double.isFinite(lon) || lon < -180 || lon > 180) {
            throw new IllegalArgumentException("Coordinate geografiche non valide");
        }
        String weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,surface_pressure,wind_speed_10m" +
                "&hourly=temperature_2m,weather_code,wind_speed_10m,precipitation_probability" +
                "&daily=uv_index_max,temperature_2m_mean,precipitation_probability_max,wind_speed_10m_mean,weather_code" +
                "&forecast_days=7&timezone=auto";
        String weatherJson;
        String dataSource = "Open-Meteo";
        try {
            weatherJson = executeHttpGET(weatherUrl);
        } catch (Exception primaryError) {
            if (!isHttpRateLimit(primaryError)) throw primaryError;
            System.err.println("Limite Open-Meteo raggiunto; uso MET Norway come fonte alternativa.");
            weatherJson = fetchMetNorwayForecast(lat, lon);
            dataSource = "MET Norway";
        }

        String currentBlock = weatherJson.substring(weatherJson.indexOf("\"current\":"));
        int dailyIndex = weatherJson.indexOf("\"daily\":");
        String dailyBlock = dailyIndex >= 0 ? weatherJson.substring(dailyIndex) : "";
        double t = parseOptionalJsonNumber(currentBlock, "\"temperature_2m\":");
        double appT = parseOptionalJsonNumber(currentBlock, "\"apparent_temperature\":");
        double hum = parseOptionalJsonNumber(currentBlock, "\"relative_humidity_2m\":");
        double press = parseOptionalJsonNumber(currentBlock, "\"surface_pressure\":");
        double rawWind = parseOptionalJsonNumber(currentBlock, "\"wind_speed_10m\":");
        int wCode = (int) parseOptionalJsonNumber(currentBlock, "\"weather_code\":");
        double[] uvValues = dailyBlock.isEmpty()
                ? new double[0] : parseNullableJsonDoubleArray(dailyBlock, "\"uv_index_max\":[");
        double uvMax = uvValues.length == 0 ? Double.NaN : uvValues[0];
        String hourlyBlock = weatherJson.substring(weatherJson.indexOf("\"hourly\":"));
        String[] hourlyTimes = parseJsonStringArray(hourlyBlock, "\"time\":[");
        double[] hourlyTemps = parseJsonDoubleArray(hourlyBlock, "\"temperature_2m\":[");
        double[] hourlyCodes = parseJsonDoubleArray(hourlyBlock, "\"weather_code\":[");
        double[] hourlyWinds = parseJsonDoubleArray(hourlyBlock, "\"wind_speed_10m\":[");
        double[] hourlyPrecipitation = parseNullableJsonDoubleArray(hourlyBlock, "\"precipitation_probability\":[");
        String[] dailyTimes = parseJsonStringArray(dailyBlock, "\"time\":[");
        double[] dailyMeanTemps = parseNullableJsonDoubleArray(dailyBlock, "\"temperature_2m_mean\":[");
        double[] dailyPrecipitation = parseNullableJsonDoubleArray(dailyBlock, "\"precipitation_probability_max\":[");
        double[] dailyMeanWinds = parseNullableJsonDoubleArray(dailyBlock, "\"wind_speed_10m_mean\":[");
        double[] dailyUvIndices = parseNullableJsonDoubleArray(dailyBlock, "\"uv_index_max\":[");
        double[] dailyCodes = parseNullableJsonDoubleArray(dailyBlock, "\"weather_code\":[");
        int forecastStart = 0;
        String currentTime = parseJsonString(currentBlock, "time");
        while (forecastStart < hourlyTimes.length && hourlyTimes[forecastStart].compareTo(currentTime) < 0) {
            forecastStart++;
        }
        if (forecastStart >= hourlyTimes.length) {
            throw new IllegalStateException("Previsioni orarie non disponibili per la località");
        }
        int forecastCount = Math.min(5, hourlyTimes.length - forecastStart);
        if (hourlyTemps.length != hourlyTimes.length || hourlyCodes.length != hourlyTimes.length
                || hourlyWinds.length != hourlyTimes.length || hourlyPrecipitation.length != hourlyTimes.length) {
            throw new IllegalArgumentException("Previsioni orarie incomplete nella risposta meteo");
        }
        if (dailyMeanTemps.length != dailyTimes.length || dailyPrecipitation.length != dailyTimes.length
                || dailyMeanWinds.length != dailyTimes.length || dailyUvIndices.length != dailyTimes.length
                || dailyCodes.length != dailyTimes.length) {
            throw new IllegalArgumentException("Previsioni giornaliere incomplete nella risposta meteo");
        }
        int dailyCount = Math.min(7, dailyTimes.length);
        DailyForecast[] dailyForecasts = new DailyForecast[dailyCount];
        for (int day = 0; day < dailyCount; day++) {
            int hourlyCountForDay = 0;
            for (String hourlyTime : hourlyTimes) {
                if (hourlyTime.startsWith(dailyTimes[day])) hourlyCountForDay++;
            }
            String[] dayTimes = new String[hourlyCountForDay];
            double[] dayTemps = new double[hourlyCountForDay];
            double[] dayCodes = new double[hourlyCountForDay];
            double[] dayPrecipitation = new double[hourlyCountForDay];
            double[] dayWinds = new double[hourlyCountForDay];
            int hour = 0;
            for (int i = 0; i < hourlyTimes.length; i++) {
                if (!hourlyTimes[i].startsWith(dailyTimes[day])) continue;
                dayTimes[hour] = hourlyTimes[i];
                dayTemps[hour] = hourlyTemps[i];
                dayCodes[hour] = hourlyCodes[i];
                dayPrecipitation[hour] = hourlyPrecipitation[i];
                dayWinds[hour] = hourlyWinds[i];
                hour++;
            }
            int weatherCode = Double.isFinite(dailyCodes[day]) ? (int) dailyCodes[day] : -1;
            dailyForecasts[day] = new DailyForecast(dailyTimes[day], dailyMeanTemps[day],
                    dailyPrecipitation[day], dailyMeanWinds[day], dailyUvIndices[day], weatherCode,
                    dayTimes, dayTemps, dayCodes, dayPrecipitation, dayWinds);
        }
        Object[][] forecastRows = new Object[forecastCount][4];
        for (int i = 0; i < forecastCount; i++) {
            int hourIndex = forecastStart + i;
            forecastRows[i] = new Object[]{
                    hourlyTimes[hourIndex].substring(11, 16),
                    resolveEmoji((int) hourlyCodes[hourIndex]),
                    Math.round(hourlyTemps[hourIndex]) + "°C",
                    Math.round(hourlyWinds[hourIndex]) + " km/h"
            };
        }
        stationCity = name;
        stationCountry = country;
        stationLat = lat;
        stationLon = lon;
        stationTemp = t;
        stationApparentTemp = appT;
        stationHumidity = hum;
        stationPressure = press;
        stationWindSpeed = rawWind;
        stationUvIndex = uvMax;
        weatherDataSourceName = dataSource;

        String desc = resolveDescription(wCode);
        String ico = resolveEmoji(wCode);
        loadAppWallpaper(ico);

        SwingUtilities.invokeLater(() -> {
            mainLocationDisplayLabel.setText(country.isEmpty() ? name : name + ", " + country);
            mainTempDisplayLabel.setText(Math.round(t) + "°");
            if (heroIconLabel != null) heroIconLabel.setText(ico);
            conditionTextLabel.setText(desc + "  ·  percepita "
                    + (Double.isFinite(appT) ? Math.round(appT) + "°" : "N/D"));

            humidityMetricBox.updateGaugeData(Double.isFinite(hum) ? Math.round(hum) + "%" : "N/D",
                    Double.isFinite(hum) ? (int) Math.max(0, Math.min(100, Math.round(hum))) : 0);
            uvMetricBox.updateGaugeData(Double.isFinite(uvMax)
                            ? String.format(Locale.ITALIAN, "%.1f UV", uvMax) : "N/D",
                    Double.isFinite(uvMax)
                            ? (int) Math.min(100, Math.max(0, Math.round(uvMax / 11.0 * 100))) : 0);
            pressureMetricBox.updateGaugeData(Double.isFinite(press) ? Math.round(press) + " hPa" : "N/D",
                    Double.isFinite(press) ? (int) Math.min(100, Math.max(0, (press - 980) * 2)) : 0);
            airQualityMetricBox.updateGaugeData("N/D", 0);

            forecastTableModel.setRowCount(0);
            for (Object[] row : forecastRows) {
                forecastTableModel.addRow(row);
            }
            if (hourlyTimelineStrip != null) {
                hourlyTimelineStrip.refreshFromModel();
            }
            if (dailyForecastPanel != null) {
                dailyForecastPanel.updateForecasts(dailyForecasts);
            }
            if (analyticsForecastPanel != null) {
                analyticsForecastPanel.updateForecasts(dailyForecasts);
            }
            setForecastDates(radarDateSelector, dailyTimes);

            refreshRadarMap(lat, lon);
            fetchAirQuality(lat, lon);

            if (rootWindow != null) {
                applyDarkTextTheme(rootWindow);
                rootWindow.repaint();
            }
        });
    }

    private static boolean isHttpRateLimit(Exception error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("HTTP 429")) return true;
        }
        return false;
    }

    private static String fetchMetNorwayForecast(double latitude, double longitude) throws Exception {
        String lat = String.format(Locale.US, "%.4f", latitude);
        String lon = String.format(Locale.US, "%.4f", longitude);
        String cacheKey = lat + "," + lon;
        long now = System.currentTimeMillis();
        synchronized (MET_NO_FORECAST_CACHE) {
            CachedWeatherForecast cached = MET_NO_FORECAST_CACHE.get(cacheKey);
            if (cached != null && now - cached.createdAt < MET_NO_CACHE_MILLIS) {
                return cached.forecastJson;
            }
        }

        String endpoint = "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat="
                + lat + "&lon=" + lon;
        String response = executeHttpGET(endpoint, null, MET_NO_USER_AGENT);
        String forecastJson = normalizeMetNorwayForecast(response);
        synchronized (MET_NO_FORECAST_CACHE) {
            MET_NO_FORECAST_CACHE.put(cacheKey,
                    new CachedWeatherForecast(System.currentTimeMillis(), forecastJson));
        }
        return forecastJson;
    }

    private static final class CachedWeatherForecast {
        private final long createdAt;
        private final String forecastJson;

        private CachedWeatherForecast(long createdAt, String forecastJson) {
            this.createdAt = createdAt;
            this.forecastJson = forecastJson;
        }
    }

    private static final class MetNorwayForecastPoint {
        private final ZonedDateTime time;
        private final double temperature;
        private final double humidity;
        private final double pressure;
        private final double windSpeedKmh;
        private final double uvIndex;
        private final int weatherCode;

        private MetNorwayForecastPoint(ZonedDateTime time, double temperature, double humidity,
                                       double pressure, double windSpeedKmh, double uvIndex,
                                       int weatherCode) {
            this.time = time;
            this.temperature = temperature;
            this.humidity = humidity;
            this.pressure = pressure;
            this.windSpeedKmh = windSpeedKmh;
            this.uvIndex = uvIndex;
            this.weatherCode = weatherCode;
        }
    }

    private static String normalizeMetNorwayForecast(String response) throws Exception {
        String[] timeSeries = extractJsonObjectsFromArray(response, "\"timeseries\":[");
        if (timeSeries.length == 0) {
            throw new IllegalStateException("MET Norway non ha restituito previsioni orarie");
        }

        ZoneId localZone = ZoneId.systemDefault();
        java.util.List<MetNorwayForecastPoint> points = new java.util.ArrayList<>(timeSeries.length);
        for (String timeEntry : timeSeries) {
            String timestamp = parseJsonString(timeEntry, "time");
            if (timestamp.isEmpty()) continue;
            ZonedDateTime localTime = Instant.parse(timestamp).atZone(localZone);
            String data = extractJsonObjectAfter(timeEntry, "\"data\":");
            String instant = extractJsonObjectAfter(data, "\"instant\":");
            String details = extractJsonObjectAfter(instant, "\"details\":");
            if (details.isEmpty()) continue;

            double temperature = parseOptionalJsonNumber(details, "\"air_temperature\":");
            if (!Double.isFinite(temperature)) continue;
            double humidity = parseOptionalJsonNumber(details, "\"relative_humidity\":");
            double pressure = parseOptionalJsonNumber(details, "\"air_pressure_at_sea_level\":");
            double windSpeed = parseOptionalJsonNumber(details, "\"wind_speed\":") * 3.6;
            double uvIndex = parseOptionalJsonNumber(details, "\"ultraviolet_index_clear_sky\":");
            int weatherCode = parseMetNorwayWeatherCode(data);
            points.add(new MetNorwayForecastPoint(localTime, temperature, humidity,
                    pressure, windSpeed, uvIndex, weatherCode));
        }
        if (points.isEmpty()) {
            throw new IllegalStateException("MET Norway non ha restituito valori meteo utilizzabili");
        }

        ZonedDateTime now = ZonedDateTime.now(localZone);
        int currentIndex = 0;
        while (currentIndex < points.size() - 1 && points.get(currentIndex).time.isBefore(now)) {
            currentIndex++;
        }
        MetNorwayForecastPoint current = points.get(currentIndex);

        Map<LocalDate, java.util.List<MetNorwayForecastPoint>> pointsByDate = new LinkedHashMap<>();
        LocalDate firstDate = current.time.toLocalDate();
        for (MetNorwayForecastPoint point : points) {
            if (point.time.toLocalDate().isBefore(firstDate)) continue;
            if (point.time.isBefore(current.time)) continue;
            if (!point.time.toLocalDate().equals(firstDate) && pointsByDate.size() >= 7) break;
            pointsByDate.computeIfAbsent(point.time.toLocalDate(), ignored -> new java.util.ArrayList<>())
                    .add(point);
        }
        if (pointsByDate.isEmpty()) {
            throw new IllegalStateException("MET Norway non ha restituito giorni di previsione");
        }

        StringBuilder json = new StringBuilder(16_384);
        json.append("{\"current\":{")
                .append("\"temperature_2m\":").append(jsonNumber(current.temperature)).append(',')
                .append("\"apparent_temperature\":null,")
                .append("\"relative_humidity_2m\":").append(jsonNumber(current.humidity)).append(',')
                .append("\"surface_pressure\":").append(jsonNumber(current.pressure)).append(',')
                .append("\"wind_speed_10m\":").append(jsonNumber(current.windSpeedKmh)).append(',')
                .append("\"weather_code\":").append(current.weatherCode).append(',')
                .append("\"time\":\"").append(formatForecastTime(current.time)).append("\"},")
                .append("\"hourly\":{");

        appendJsonStringArray(json, "time", points.stream()
                .filter(point -> !point.time.isBefore(current.time))
                .map(point -> formatForecastTime(point.time)).toArray(String[]::new));
        appendJsonNumberArray(json, "temperature_2m", points.stream()
                .filter(point -> !point.time.isBefore(current.time))
                .mapToDouble(point -> point.temperature).toArray());
        appendJsonNumberArray(json, "weather_code", points.stream()
                .filter(point -> !point.time.isBefore(current.time))
                .mapToDouble(point -> point.weatherCode).toArray());
        appendJsonNumberArray(json, "wind_speed_10m", points.stream()
                .filter(point -> !point.time.isBefore(current.time))
                .mapToDouble(point -> point.windSpeedKmh).toArray());
        appendJsonNumberArray(json, "precipitation_probability", points.stream()
                .filter(point -> !point.time.isBefore(current.time))
                .mapToDouble(point -> Double.NaN).toArray());
        json.append("},\"daily\":{");

        LocalDate[] dates = pointsByDate.keySet().toArray(new LocalDate[0]);
        appendJsonStringArray(json, "time", Arrays.stream(dates).map(LocalDate::toString).toArray(String[]::new));
        double[] dailyMeanTemps = new double[dates.length];
        double[] dailyMeanWinds = new double[dates.length];
        double[] dailyUvMax = new double[dates.length];
        double[] dailyWeatherCodes = new double[dates.length];
        double[] dailyPrecipitation = new double[dates.length];
        Arrays.fill(dailyUvMax, Double.NaN);
        Arrays.fill(dailyPrecipitation, Double.NaN);
        for (int i = 0; i < dates.length; i++) {
            java.util.List<MetNorwayForecastPoint> dailyPoints = pointsByDate.get(dates[i]);
            dailyMeanTemps[i] = averageFinite(dailyPoints.stream()
                    .mapToDouble(point -> point.temperature).toArray());
            dailyMeanWinds[i] = averageFinite(dailyPoints.stream()
                    .mapToDouble(point -> point.windSpeedKmh).toArray());
            dailyUvMax[i] = maximumFinite(dailyPoints.stream()
                    .mapToDouble(point -> point.uvIndex).toArray());
            MetNorwayForecastPoint midday = dailyPoints.stream()
                    .min(java.util.Comparator.comparingLong(point ->
                            Math.abs(point.time.getHour() - 12) * 60L + point.time.getMinute()))
                    .orElse(dailyPoints.get(0));
            dailyWeatherCodes[i] = midday.weatherCode;
        }
        appendJsonNumberArray(json, "uv_index_max", dailyUvMax);
        appendJsonNumberArray(json, "temperature_2m_mean", dailyMeanTemps);
        appendJsonNumberArray(json, "precipitation_probability_max", dailyPrecipitation);
        appendJsonNumberArray(json, "wind_speed_10m_mean", dailyMeanWinds);
        appendJsonNumberArray(json, "weather_code", dailyWeatherCodes);
        json.setLength(json.length() - 1);
        return json.append("}}").toString();
    }

    private static String[] extractJsonObjectsFromArray(String json, String marker) {
        int markerIndex = json.indexOf(marker);
        if (markerIndex < 0) return new String[0];
        int arrayStart = markerIndex + marker.length() - 1;
        java.util.List<String> objects = new java.util.ArrayList<>();
        int depth = 0;
        int objectStart = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = arrayStart + 1; i < json.length(); i++) {
            char character = json.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') inString = false;
                continue;
            }
            if (character == '"') {
                inString = true;
            } else if (character == '{') {
                if (depth == 0) objectStart = i;
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0 && objectStart >= 0) {
                    objects.add(json.substring(objectStart, i + 1));
                    objectStart = -1;
                }
            } else if (character == ']' && depth == 0) {
                break;
            }
        }
        return objects.toArray(new String[0]);
    }

    private static String extractJsonObjectAfter(String json, String marker) {
        int markerIndex = json.indexOf(marker);
        if (markerIndex < 0) return "";
        int objectStart = json.indexOf('{', markerIndex + marker.length());
        if (objectStart < 0) return "";
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = objectStart; i < json.length(); i++) {
            char character = json.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') inString = false;
                continue;
            }
            if (character == '"') inString = true;
            else if (character == '{') depth++;
            else if (character == '}' && --depth == 0) return json.substring(objectStart, i + 1);
        }
        return "";
    }

    private static int parseMetNorwayWeatherCode(String data) {
        String period = extractJsonObjectAfter(data, "\"next_1_hours\":");
        if (period.isEmpty()) period = extractJsonObjectAfter(data, "\"next_6_hours\":");
        if (period.isEmpty()) period = extractJsonObjectAfter(data, "\"next_12_hours\":");
        String symbol = parseJsonString(period, "symbol_code").toLowerCase(Locale.ROOT);
        if (symbol.contains("thunder")) return 95;
        if (symbol.contains("sleet")) return 66;
        if (symbol.contains("snow")) return symbol.contains("heavy") ? 75 : 71;
        if (symbol.contains("rain")) return symbol.contains("heavy") ? 65 : 61;
        if (symbol.contains("fog")) return 45;
        if (symbol.contains("cloudy")) return symbol.contains("partly") ? 2 : 3;
        if (symbol.contains("fair")) return 1;
        if (symbol.contains("clearsky")) return 0;
        return -1;
    }

    private static String formatForecastTime(ZonedDateTime time) {
        return time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
    }

    private static double averageFinite(double[] values) {
        double sum = 0;
        int count = 0;
        for (double value : values) {
            if (!Double.isFinite(value)) continue;
            sum += value;
            count++;
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    private static double maximumFinite(double[] values) {
        double maximum = Double.NEGATIVE_INFINITY;
        for (double value : values) {
            if (Double.isFinite(value)) maximum = Math.max(maximum, value);
        }
        return maximum == Double.NEGATIVE_INFINITY ? Double.NaN : maximum;
    }

    private static String jsonNumber(double value) {
        return Double.isFinite(value) ? String.format(Locale.US, "%.4f", value) : "null";
    }

    private static void appendJsonStringArray(StringBuilder json, String key, String[] values) {
        json.append('"').append(key).append("\":[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) json.append(',');
            json.append('"').append(values[i]).append('"');
        }
        json.append("],");
    }

    private static void appendJsonNumberArray(StringBuilder json, String key, double[] values) {
        json.append('"').append(key).append("\":[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) json.append(',');
            json.append(jsonNumber(values[i]));
        }
        json.append("],");
    }

    private static void fetchAirQuality(double latitude, double longitude) {
        new Thread(() -> {
            try {
                String endpoint = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude="
                        + latitude + "&longitude=" + longitude
                        + "&current=european_aqi,pm2_5,pm10,nitrogen_dioxide"
                        + "&hourly=european_aqi,pm2_5,pm10,nitrogen_dioxide"
                        + "&forecast_days=7&timezone=auto";
                String response = executeHttpGET(endpoint);
                int currentIndex = response.indexOf("\"current\":");
                int hourlyIndex = response.indexOf("\"hourly\":");
                if (currentIndex < 0) {
                    throw new IllegalStateException("Risposta qualità dell'aria senza dati correnti");
                }
                if (hourlyIndex < 0) {
                    throw new IllegalStateException("Risposta qualità dell'aria senza serie orarie");
                }
                String current = response.substring(currentIndex);
                String hourly = response.substring(hourlyIndex);
                double aqi = parseRequiredJsonNumber(current, "\"european_aqi\":");
                double pm25 = parseRequiredJsonNumber(current, "\"pm2_5\":");
                double pm10 = parseRequiredJsonNumber(current, "\"pm10\":");
                double no2 = parseRequiredJsonNumber(current, "\"nitrogen_dioxide\":");
                String[] times = parseJsonStringArray(hourly, "\"time\":[");
                double[][] trends = {
                        parseNullableJsonDoubleArray(hourly, "\"european_aqi\":["),
                        parseNullableJsonDoubleArray(hourly, "\"pm2_5\":["),
                        parseNullableJsonDoubleArray(hourly, "\"pm10\":["),
                        parseNullableJsonDoubleArray(hourly, "\"nitrogen_dioxide\":[")
                };
                for (double[] trend : trends) {
                    if (trend.length != times.length) {
                        throw new IllegalStateException("Serie orarie della qualità dell'aria incomplete");
                    }
                }
                SwingUtilities.invokeLater(() -> {
                    stationEuropeanAqi = aqi;
                    stationPm25 = pm25;
                    stationPm10 = pm10;
                    stationNo2 = no2;
                    String category = airQualityCategory(aqi);
                    airQualitySummaryLabel.setText(category);
                    airQualitySummaryLabel.setForeground(airQualityColor(aqi));
                    airQualityAqiLabel.setText(String.format(Locale.ITALIAN, "Indice europeo AQI: %.0f", aqi));
                    airQualityPm25Label.setText(String.format(Locale.ITALIAN, "PM2.5: %.1f µg/m³", pm25));
                    airQualityPm10Label.setText(String.format(Locale.ITALIAN, "PM10: %.1f µg/m³", pm10));
                    airQualityNo2Label.setText(String.format(Locale.ITALIAN, "NO₂: %.1f µg/m³", no2));
                    setForecastDates(airQualityDateSelector, uniqueDates(times));
                    ForecastDateOption selectedDate =
                            (ForecastDateOption) airQualityDateSelector.getSelectedItem();
                    if (selectedDate != null) {
                        airQualityTrendsTitleLabel.setText("Andamento orario · " + selectedDate.displayDate());
                        airQualityTrendsPanel.setSelectedDate(selectedDate.date);
                    }
                    airQualityTrendsPanel.updateData(times, trends);
                    airQualityMetricBox.updateGaugeData(String.format(Locale.ITALIAN, "%.0f AQI", aqi),
                            (int) Math.max(0, Math.min(100, Math.round(aqi))));
                    if (rootWindow != null) applyDarkTextTheme(rootWindow);
                });
            } catch (Exception ex) {
                System.err.println("Qualità dell'aria non disponibile: " + ex.getMessage());
                SwingUtilities.invokeLater(() -> {
                    stationEuropeanAqi = Double.NaN;
                    stationPm25 = Double.NaN;
                    stationPm10 = Double.NaN;
                    stationNo2 = Double.NaN;
                    airQualitySummaryLabel.setText("Dati temporaneamente non disponibili");
                    airQualitySummaryLabel.setForeground(new Color(104, 112, 121));
                    airQualityAqiLabel.setText("Indice europeo AQI: --");
                    airQualityPm25Label.setText("PM2.5: -- µg/m³");
                    airQualityPm10Label.setText("PM10: -- µg/m³");
                    airQualityNo2Label.setText("NO₂: -- µg/m³");
                    airQualityTrendsPanel.updateData(new String[0], new double[4][0]);
                    airQualityMetricBox.updateGaugeData("N/D", 0);
                    if (rootWindow != null) applyDarkTextTheme(rootWindow);
                });
            }
        }, "air-quality-fetch").start();
    }

    private static String airQualityCategory(double aqi) {
        if (aqi <= 20) return "Buona";
        if (aqi <= 40) return "Discreta";
        if (aqi <= 60) return "Moderata";
        if (aqi <= 80) return "Scarsa";
        if (aqi <= 100) return "Molto scarsa";
        return "Estremamente scarsa";
    }

    private static Color airQualityColor(double aqi) {
        if (aqi <= 20) return new Color(48, 142, 100);
        if (aqi <= 40) return new Color(89, 139, 91);
        if (aqi <= 60) return new Color(184, 137, 54);
        if (aqi <= 80) return new Color(190, 94, 61);
        return new Color(161, 65, 72);
    }

    private static void refreshRadarMap(double latitude, double longitude) {
        if (radarMapPanel != null) {
            radarMapPanel.refresh(latitude, longitude);
        }
    }

    private static String executeHttpGET(String endpoint) throws Exception {
        return executeHttpGET(endpoint, null);
    }

    private static String executeHttpGET(String endpoint, RadarLoadToken requestToken) throws Exception {
        return executeHttpGET(endpoint, requestToken, null);
    }

    private static String executeHttpGET(String endpoint, RadarLoadToken requestToken,
                                         String userAgent) throws Exception {
        HttpsURLConnection conn = openSecureConnection(endpoint);
        URL url = conn.getURL();
        if (requestToken != null) requestToken.register(conn);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(6000);
        if (userAgent != null && !userAgent.isBlank()) {
            conn.setRequestProperty("User-Agent", userAgent);
        }

        try {
            int responseCode = conn.getResponseCode();
            java.io.InputStream responseStream = responseCode >= 200 && responseCode < 300
                    ? conn.getInputStream() : conn.getErrorStream();
            if (responseStream == null) {
                throw new java.io.IOException("Risposta HTTP " + responseCode + " da " + url.getHost());
            }
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(responseStream, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                char[] buffer = new char[8192];
                int count;
                while ((count = br.read(buffer)) != -1) {
                    if (requestToken != null) requestToken.check();
                    if (sb.length() + count > MAX_HTTP_RESPONSE_CHARS) {
                        throw new java.io.IOException("La risposta del servizio supera la dimensione massima consentita");
                    }
                    sb.append(buffer, 0, count);
                }
                String responseBody = sb.toString();
                if (responseCode < 200 || responseCode >= 300) {
                    String details = responseBody.length() > 240
                            ? responseBody.substring(0, 240) : responseBody;
                    throw new java.io.IOException("Risposta HTTP " + responseCode + " da "
                            + url.getHost() + (details.isBlank() ? "" : ": " + details));
                }
                return responseBody;
            }
        } finally {
            if (requestToken != null) requestToken.unregister(conn);
            conn.disconnect();
        }
    }

    static HttpsURLConnection openSecureConnection(String endpoint) throws java.io.IOException {
        final URI uri;
        try {
            uri = new URI(endpoint);
        } catch (URISyntaxException ex) {
            throw new java.io.IOException("URL del servizio non valido", ex);
        }
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || host.isBlank()
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || !isAllowedRemoteHost(host)) {
            throw new java.io.IOException("Connessione HTTPS non consentita verso l'indirizzo richiesto");
        }
        HttpsURLConnection connection = (HttpsURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        return connection;
    }

    static boolean isAllowedRemoteHost(String host) {
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        return ALLOWED_REMOTE_HOSTS.contains(normalizedHost)
                || normalizedHost.equals("rainviewer.com")
                || normalizedHost.endsWith(".rainviewer.com");
    }

    static String normalizeDisplayName(String value) {
        if (value == null) return DEFAULT_USER_NAME;
        StringBuilder normalized = new StringBuilder();
        value.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .limit(40)
                .forEach(normalized::appendCodePoint);
        String name = normalized.toString().trim();
        return name.isEmpty() ? DEFAULT_USER_NAME : name;
    }

    static String loadDisplayName(Preferences preferences) {
        String savedName = preferences.get(USER_NAME_PREFERENCE_KEY, DEFAULT_USER_NAME);
        return normalizeDisplayName(savedName);
    }

    static void saveDisplayName(Preferences preferences, String value) throws BackingStoreException {
        preferences.put(USER_NAME_PREFERENCE_KEY, normalizeDisplayName(value));
        preferences.flush();
    }

    private static String loadUserName() {
        try {
            return loadDisplayName(userPreferences());
        } catch (SecurityException | IllegalStateException ex) {
            System.err.println("Impossibile caricare il nome utente salvato: " + ex.getMessage());
            return DEFAULT_USER_NAME;
        }
    }

    private static Preferences userPreferences() {
        return Preferences.userRoot().node("it/meteobetti/ultimateos");
    }

    private static double parseJsonNumber(String json, String key) {
        try {
            int start = json.indexOf(key) + key.length();
            int end = json.indexOf(",", start);
            if (end == -1) end = json.indexOf("}", start);
            String val = json.substring(start, end).replace("[", "").replace("]", "").replace("\"", "").trim();
            return Double.parseDouble(val);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static double parseOptionalJsonNumber(String json, String key) {
        int keyIndex = json.indexOf(key);
        if (keyIndex < 0) return Double.NaN;
        int valueStart = keyIndex + key.length();
        int comma = json.indexOf(',', valueStart);
        int objectEnd = json.indexOf('}', valueStart);
        int arrayEnd = json.indexOf(']', valueStart);
        int valueEnd = comma < 0 ? objectEnd : objectEnd < 0 ? comma : Math.min(comma, objectEnd);
        if (arrayEnd >= 0 && (valueEnd < 0 || arrayEnd < valueEnd)) valueEnd = arrayEnd;
        if (valueEnd < 0) return Double.NaN;
        String value = json.substring(valueStart, valueEnd).replace("[", "").trim();
        if (value.equals("null") || value.isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }

    private static double parseRequiredJsonNumber(String json, String key) {
        int keyIndex = json.indexOf(key);
        if (keyIndex < 0) {
            throw new IllegalArgumentException("Dato mancante nella risposta: " + key);
        }
        int valueStart = keyIndex + key.length();
        int comma = json.indexOf(',', valueStart);
        int objectEnd = json.indexOf('}', valueStart);
        int valueEnd = comma < 0 ? objectEnd : objectEnd < 0 ? comma : Math.min(comma, objectEnd);
        if (valueEnd < 0) {
            throw new IllegalArgumentException("Dato incompleto nella risposta: " + key);
        }
        String value = json.substring(valueStart, valueEnd).trim();
        if (value.equals("null") || value.isEmpty()) {
            throw new IllegalArgumentException("Dato non disponibile nella risposta: " + key);
        }
        return Double.parseDouble(value);
    }

    private static String parseJsonString(String json, String key) {
        try {
            String pattern = "\"" + key + "\":\"";
            int start = json.indexOf(pattern) + pattern.length();
            int end = json.indexOf("\"", start);
            return json.substring(start, end);
        } catch (Exception e) {
            return "";
        }
    }

    private static String[] parseJsonStringArray(String json, String key) {
        String[] values = jsonArrayValues(json, key);
        for (int i = 0; i < values.length; i++) {
            String value = values[i].trim();
            if (value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
                throw new IllegalArgumentException("Valore orario non valido nella risposta meteo");
            }
            values[i] = value.substring(1, value.length() - 1);
        }
        return values;
    }

    private static double[] parseJsonDoubleArray(String json, String key) {
        String[] values = jsonArrayValues(json, key);
        double[] numbers = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            numbers[i] = Double.parseDouble(values[i].trim());
        }
        return numbers;
    }

    private static double[] parseNullableJsonDoubleArray(String json, String key) {
        String[] values = jsonArrayValues(json, key);
        double[] numbers = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            String value = values[i].trim();
            numbers[i] = value.equals("null") || value.isEmpty()
                    ? Double.NaN : Double.parseDouble(value);
        }
        return numbers;
    }

    private static String[] jsonArrayValues(String json, String key) {
        int start = json.indexOf(key);
        if (start < 0) {
            throw new IllegalArgumentException("Dato meteo mancante: " + key);
        }
        start += key.length();
        int end = json.indexOf(']', start);
        if (end < 0) {
            throw new IllegalArgumentException("Array meteo incompleto: " + key);
        }
        String content = json.substring(start, end).trim();
        return content.isEmpty() ? new String[0] : content.split(",");
    }

    private static String resolveDescription(int code) {
        if (code < 0) return "Condizione non disponibile";
        if (code == 0) return "Cielo Sereno";
        if (code <= 3) return "Poco Nuvoloso";
        if (code <= 48) return "Nebbia / Foschia";
        if (code <= 67) return "Pioggia / Rovesci";
        if (code <= 77) return "Nevicata";
        return "Temporale";
    }

    private static String resolveEmoji(int code) {
        if (code < 0) return "☁️";
        if (code == 0) return "☀️";
        if (code <= 3) return "🌤️";
        if (code <= 48) return "🌫️";
        if (code <= 67) return "🌧️";
        if (code <= 77) return "❄️";
        return "⛈️";
    }

    private static String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static void openMetricInfo(String title, String value, String description) {
        openTelemetryPopup(rootWindow, title,
                "<html><body style='color:#e6eaf0; font-family:sans-serif; width:300px;'>" +
                "<p style='font-size:22px; color:#ffffff;'><b>" + escapeHtml(value) + "</b></p>" +
                "<p style='color:#d2d8e1;'>" + escapeHtml(description) + "</p>" +
                "<hr style='border:1px solid #555c67;'>" +
                "<p style='color:#aeb7c3; font-size:11px;'>Le condizioni possono variare localmente.</p>" +
                "</body></html>");
    }

    private static void openTelemetryPopup(JFrame parent, String title, String htmlContent) {
        JDialog dialog = new JDialog(parent, title, true);
        dialog.setUndecorated(true);
        dialog.setBackground(new Color(0, 0, 0, 0));
        dialog.setResizable(false);

        GenieGlassPopupContainer container = new GenieGlassPopupContainer();
        container.setLayout(new BorderLayout(0, 12));
        container.setBorder(new EmptyBorder(16, 18, 14, 18));

        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        JLabel eyebrow = new JLabel("METEOBETTI  ·  DETTAGLIO");
        eyebrow.setFont(uiFont(Font.BOLD, 9f));
        eyebrow.setForeground(new Color(169, 181, 196));
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(uiFont(Font.BOLD, 18f));
        titleLabel.setForeground(new Color(250, 251, 253));
        heading.add(eyebrow);
        heading.add(Box.createVerticalStrut(4));
        heading.add(titleLabel);
        heading.add(Box.createVerticalStrut(10));
        JPanel separator = new JPanel() {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g2 = (Graphics2D) graphics.create();
                g2.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 100),
                        getWidth(), 0, new Color(255, 255, 255, 8)));
                g2.fillRoundRect(0, 0, getWidth(), 1, 1, 1);
                g2.dispose();
            }
        };
        separator.setOpaque(false);
        separator.setPreferredSize(new Dimension(1, 1));
        separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        heading.add(separator);

        JLabel label = new JLabel(htmlContent);
        label.setFont(uiFont(Font.PLAIN, 13f));
        label.setBorder(new EmptyBorder(2, 2, 2, 10));
        JScrollPane scroll = new JScrollPane(label);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        styleScrollBar(scroll.getVerticalScrollBar());

        JButton closeBtn = new JButton("Chiudi");
        styleDiscreetButton(closeBtn);
        closeBtn.setFont(uiFont(Font.BOLD, 12f));
        closeBtn.addActionListener(e -> dialog.dispose());

        container.add(heading, BorderLayout.NORTH);
        container.add(scroll, BorderLayout.CENTER);
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        footer.setOpaque(false);
        footer.add(closeBtn);
        container.add(footer, BorderLayout.SOUTH);
        dialog.add(container);

        int targetWidth = 410;
        int targetHeight = Math.max(260, Math.min(520, label.getPreferredSize().height + 145));
        dialog.setSize(targetWidth, targetHeight);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    static class GenieGlassPopupContainer extends JPanel {
        GenieGlassPopupContainer() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(0, 0, 0, 76));
            g2.fillRoundRect(3, 5, getWidth() - 6, getHeight() - 8, 34, 34);
            g2.setPaint(new GradientPaint(
                    0, 0, new Color(79, 85, 96, 184),
                    0, getHeight(), new Color(10, 13, 18, 202)
            ));
            g2.fillRoundRect(0, 0, getWidth() - 2, getHeight() - 3, 34, 34);
            Shape previousClip = g2.getClip();
            g2.clip(new java.awt.geom.RoundRectangle2D.Float(1, 1, getWidth() - 4,
                    getHeight() - 5, 34, 34));
            g2.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 45),
                    0, getHeight() / 3f, new Color(255, 255, 255, 0)));
            g2.fillRect(0, 0, getWidth(), getHeight() / 3);
            g2.setClip(previousClip);
            paintGlassOutline(g2, 0, 0, getWidth() - 3, getHeight() - 4, 34);
            g2.dispose();
        }
    }

    private static JPanel createLiquidGlassCardPanel() {
        JPanel p = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                paintFrostedCard(g2, getWidth(), getHeight(), 28);
                g2.dispose();
            }
        };
        p.setOpaque(false);
        return p;
    }

    static class AppNavBar extends JPanel {
        private String selected = "DASHBOARD";
        private final String[] keys = {"DASHBOARD", "ANALITICA", "RADAR", "ARIA"};
        private final String[] labels = {"Meteo", "Analitica", "Radar", "Aria"};
        private final String[] glyphs = {"weather", "chart", "radar", "air"};

        AppNavBar() {
            setOpaque(false);
            setPreferredSize(new Dimension(460, 82));
            setLayout(new GridLayout(1, 4, 2, 0));
            setBorder(new EmptyBorder(7, 8, 7, 8));
            rebuild();
        }

        void select(String key) {
            selected = key;
            rebuild();
            pageCardLayout.show(mainCardContainer, key);
        }

        private void rebuild() {
            removeAll();
            for (int i = 0; i < keys.length; i++) {
                final String key = keys[i];
                final boolean on = key.equals(selected);
                JButton button = new JButton();
                button.setLayout(new BoxLayout(button, BoxLayout.Y_AXIS));
                button.setFocusPainted(false);
                button.setBorderPainted(false);
                button.setContentAreaFilled(false);
                button.setCursor(new Cursor(Cursor.HAND_CURSOR));
                button.setOpaque(false);
                button.setBorder(new EmptyBorder(5, 2, 4, 2));
                NavGlyph glyph = new NavGlyph(glyphs[i], on);
                glyph.setAlignmentX(Component.CENTER_ALIGNMENT);
                JLabel label = new JLabel(labels[i]);
                label.setFont(uiFont(on ? Font.BOLD : Font.PLAIN, 10f));
                label.setForeground(on ? new Color(247, 249, 252) : new Color(157, 163, 173));
                label.setAlignmentX(Component.CENTER_ALIGNMENT);
                button.add(glyph);
                button.add(Box.createVerticalStrut(3));
                button.add(label);
                button.addActionListener(e -> select(key));

                JPanel wrap = new JPanel(new GridBagLayout()) {
                    @Override
                    protected void paintComponent(Graphics g) {
                        super.paintComponent(g);
                        if (!on) return;
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        float centerX = getWidth() / 2f;
                        float centerY = getHeight() * 0.39f;
                        float radius = Math.max(24f, getWidth() * 0.82f);
                        g2.setPaint(new java.awt.RadialGradientPaint(
                                centerX, centerY, radius,
                                new float[]{0f, 0.38f, 0.75f, 1f},
                                new Color[]{
                                        new Color(91, 157, 255, 82),
                                        new Color(67, 132, 247, 44),
                                        new Color(47, 110, 232, 12),
                                        new Color(47, 110, 232, 0)
                                }));
                        g2.fillOval((int) (centerX - radius), (int) (centerY - radius),
                                (int) (radius * 2), (int) (radius * 2));
                        g2.dispose();
                    }
                };
                wrap.setOpaque(false);
                wrap.add(button);
                add(wrap);
            }
            revalidate();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = getWidth() - 1;
            int height = getHeight() - 1;
            g2.setColor(new Color(0, 0, 0, 65));
            g2.fillRoundRect(2, 3, width - 4, height - 5, 40, 40);
            g2.setPaint(new GradientPaint(0, 1, new Color(112, 121, 137, 105),
                    0, height, new Color(24, 27, 34, 170)));
            g2.fillRoundRect(0, 0, width, height, 40, 40);

            Shape previousClip = g2.getClip();
            g2.clip(new java.awt.geom.RoundRectangle2D.Float(1, 1, width - 2, height - 2, 40, 40));
            g2.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 34),
                    0, height * 0.68f, new Color(255, 255, 255, 0)));
            g2.fillRect(0, 0, width, height);
            g2.setClip(previousClip);

            paintGlassOutline(g2, 0, 0, width, height, 40);

            g2.setPaint(new GradientPaint(width * 0.18f, 1,
                    new Color(255, 255, 255, 38), width * 0.82f, 1,
                    new Color(255, 255, 255, 0)));
            g2.setStroke(new BasicStroke(0.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine((int) (width * 0.18), 1, (int) (width * 0.82), 1);
            g2.dispose();
        }
    }

    private static final class NavGlyph extends JComponent {
        private final String type;
        private final boolean active;

        private NavGlyph(String type, boolean active) {
            this.type = type;
            this.active = active;
            setPreferredSize(new Dimension(24, 24));
            setMinimumSize(new Dimension(24, 24));
            setMaximumSize(new Dimension(24, 24));
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(active ? new Color(250, 252, 255) : new Color(151, 158, 169));
            g2.setStroke(new BasicStroke(active ? 2f : 1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (type) {
                case "weather":
                    g2.drawOval(8, 2, 8, 8);
                    g2.drawLine(12, 0, 12, 2);
                    g2.drawLine(12, 10, 12, 12);
                    g2.drawLine(5, 6, 7, 6);
                    g2.drawLine(17, 6, 19, 6);
                    Path2D cloud = new Path2D.Double();
                    cloud.moveTo(4, 19);
                    cloud.curveTo(2, 19, 2, 15, 5, 14);
                    cloud.curveTo(5, 10, 11, 9, 13, 13);
                    cloud.curveTo(18, 11, 21, 16, 19, 19);
                    cloud.closePath();
                    if (active) {
                        g2.fill(cloud);
                        g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g2.drawLine(7, 3, 6, 1);
                        g2.drawLine(19, 3, 21, 1);
                    } else {
                        g2.draw(cloud);
                    }
                    break;
                case "chart":
                    g2.drawLine(3, 21, 3, 3);
                    g2.drawLine(3, 21, 22, 21);
                    Path2D trend = new Path2D.Double();
                    trend.moveTo(5, 16);
                    trend.lineTo(10, 11);
                    trend.lineTo(14, 14);
                    trend.lineTo(20, 6);
                    g2.draw(trend);
                    break;
                case "radar":
                    g2.drawOval(3, 3, 18, 18);
                    g2.drawOval(7, 7, 10, 10);
                    g2.drawLine(12, 12, 18, 6);
                    g2.drawLine(12, 2, 12, 22);
                    g2.drawLine(2, 12, 22, 12);
                    break;
                case "air":
                    Path2D leaf = new Path2D.Double();
                    leaf.moveTo(4, 19);
                    leaf.curveTo(3, 7, 10, 2, 21, 3);
                    leaf.curveTo(22, 14, 16, 21, 4, 19);
                    leaf.closePath();
                    g2.draw(leaf);
                    g2.drawLine(5, 19, 17, 7);
                    g2.drawLine(10, 14, 9, 9);
                    g2.drawLine(13, 11, 18, 12);
                    break;
                default:
                    break;
            }
            g2.dispose();
        }
    }

    static class HourlyTimelineStrip extends JPanel {
        HourlyTimelineStrip() {
            setOpaque(false);
            setLayout(new GridLayout(1, 5, 8, 0));
            setPreferredSize(new Dimension(460, 118));
            refreshFromModel();
        }

        void refreshFromModel() {
            removeAll();
            int rows = forecastTableModel == null ? 0 : forecastTableModel.getRowCount();
            int count = Math.max(1, Math.min(5, rows));
            for (int i = 0; i < count; i++) {
                if (rows == 0) {
                    add(hourCell("--:--", "--", "--", "--"));
                } else {
                    add(hourCell(
                            String.valueOf(forecastTableModel.getValueAt(i, 0)),
                            String.valueOf(forecastTableModel.getValueAt(i, 1)),
                            String.valueOf(forecastTableModel.getValueAt(i, 2)),
                            String.valueOf(forecastTableModel.getValueAt(i, 3))));
                }
            }
            revalidate();
            repaint();
        }

        void updateFromForecast(DailyForecast forecast) {
            removeAll();
            for (int i = 0; i < forecast.hourlyTimes.length; i++) {
                String time = forecast.hourlyTimes[i].substring(11, 16);
                String icon = Double.isFinite(forecast.hourlyWeatherCodes[i])
                        ? resolveEmoji((int) forecast.hourlyWeatherCodes[i]) : "—";
                String temperature = Double.isFinite(forecast.hourlyTemperatures[i])
                        ? String.format(Locale.ITALIAN, "%.0f°", forecast.hourlyTemperatures[i]) : "--°";
                String wind = Double.isFinite(forecast.hourlyWind[i])
                        ? String.format(Locale.ITALIAN, "Vento %.0f km/h", forecast.hourlyWind[i]) : "Vento --";
                add(hourCell(time, icon, temperature, wind));
            }
            revalidate();
            repaint();
        }

        private JPanel hourCell(String time, String icon, String temp, String wind) {
            JPanel cell = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(new Color(10, 13, 18, 190));
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18);
                    g2.setColor(new Color(255, 255, 255, 76));
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
                    g2.dispose();
                    super.paintComponent(g);
                }
            };
            cell.setOpaque(false);
            cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
            cell.setBorder(new EmptyBorder(10, 4, 10, 4));

            JLabel timeLabel = centeredLabel(time, Font.BOLD, 12f, new Color(208, 216, 226));
            JLabel iconLabel = centeredLabel(icon, Font.PLAIN, 22f, new Color(246, 248, 251));
            JLabel tempLabel = centeredLabel(temp, Font.BOLD, 14f, new Color(250, 251, 253));
            JLabel windLabel = centeredLabel(wind, Font.BOLD, 11f, new Color(198, 207, 218));
            cell.add(timeLabel);
            cell.add(Box.createVerticalStrut(4));
            cell.add(iconLabel);
            cell.add(Box.createVerticalStrut(4));
            cell.add(tempLabel);
            cell.add(Box.createVerticalStrut(2));
            cell.add(windLabel);
            return cell;
        }

        private JLabel centeredLabel(String text, int style, float size, Color color) {
            JLabel label = new JLabel(text, SwingConstants.CENTER);
            label.setAlignmentX(Component.CENTER_ALIGNMENT);
            label.setFont(uiFont(style, size));
            label.setForeground(color);
            return label;
        }
    }

    static class TelemetryGaugeBox extends JPanel {
        private String title;
        private String value;
        private int percent;
        private final Color accent;

        public TelemetryGaugeBox(String title, String value, int percent, Runnable action) {
            this.title = title;
            this.value = value;
            this.percent = Math.max(0, Math.min(100, percent));
            this.accent = title.contains("Umidità") ? new Color(64, 205, 224)
                    : title.contains("UV") ? new Color(255, 190, 86)
                    : title.contains("Pressione") ? new Color(129, 163, 255)
                    : title.contains("Temperatura") ? new Color(226, 151, 76)
                    : title.contains("Pioggia") ? new Color(75, 157, 211)
                    : title.contains("Vento") ? new Color(82, 174, 139)
                    : new Color(94, 218, 156);
            setOpaque(false);
            setPreferredSize(new Dimension(230, 100));
            setToolTipText("Clicca per saperne di più");

            if (action != null) {
                setCursor(new Cursor(Cursor.HAND_CURSOR));
                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) { action.run(); }
                });
            }
        }

        public void updateGaugeData(String newVal, int newPerc) {
            this.value = newVal;
            this.percent = Math.max(0, Math.min(100, newPerc));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            paintFrostedCard(g2, getWidth(), getHeight(), 26);

            g2.setFont(uiFont(Font.BOLD, 12f));
            FontMetrics fm = g2.getFontMetrics();
            int titleWidth = fm.stringWidth(title);
            g2.setColor(new Color(228, 234, 241));
            g2.drawString(title, Math.max(8, (getWidth() - titleWidth) / 2), 22);

            int diameter = Math.max(1, Math.min(62, getHeight() - 38));
            int x = (getWidth() - diameter) / 2;
            int y = 26 + Math.max(0, (getHeight() - 32 - diameter) / 2);
            g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 36));
            g2.fillOval(x - 8, y - 8, diameter + 16, diameter + 16);

            g2.setStroke(new BasicStroke(5.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(205, 215, 226, 70));
            g2.draw(new Arc2D.Float(x, y, diameter, diameter, 90, -270, Arc2D.OPEN));
            g2.setColor(accent);
            g2.draw(new Arc2D.Float(x, y, diameter, diameter, 90, -(int) (270 * percent / 100.0), Arc2D.OPEN));

            g2.setColor(new Color(250, 251, 253));
            int valueFontSize = value.length() > 7 ? 10 : 13;
            g2.setFont(uiFont(Font.BOLD, valueFontSize));
            fm = g2.getFontMetrics();
            int valueWidth = fm.stringWidth(value);
            g2.drawString(value, (getWidth() - valueWidth) / 2, y + (diameter + fm.getAscent() - fm.getDescent()) / 2);

            g2.dispose();
        }
    }
}
