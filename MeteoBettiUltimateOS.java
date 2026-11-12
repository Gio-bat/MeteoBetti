import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.plaf.basic.ComboPopup;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
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
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.StringReader;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import javax.net.ssl.HttpsURLConnection;

/**
 * METEOBETTI ULTIMATE OS - Dashboard meteo desktop con interfaccia in vetro.
 */
public class MeteoBettiUltimateOS {

    private static final int RADAR_MAP_SIZE = 512;
    private static final int RADAR_RADIUS_KM = 100;
    static final int MAX_RADAR_TILE_ZOOM = 7;
    private static final double EARTH_CIRCUMFERENCE_METERS = 40_075_016.686;
    private static final String MET_NO_USER_AGENT = "MeteoBetti/1.0 (desktop weather application)";
    private static final long MET_NO_CACHE_MILLIS = TimeUnit.MINUTES.toMillis(30);
    private static final int MAX_HTTP_RESPONSE_CHARS = 4 * 1024 * 1024;
    private static final int MAX_TILE_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_TILE_DIMENSION = 1024;
    private static final String DEFAULT_USER_NAME = "Utente";
    private static final String USER_NAME_PREFERENCE_KEY = "displayName";
    private static final String LIGHT_THEME_PREFERENCE_KEY = "lightTheme";
    private static final String PRESERVE_THEME_FOREGROUND_PROPERTY = "meteobetti.preserveThemeForeground";
    private static final String ALERT_SEVERITY_PROPERTY = "meteobetti.alertSeverity";
    private static final Set<String> ALLOWED_REMOTE_HOSTS = Set.of(
            "api.open-meteo.com",
            "air-quality-api.open-meteo.com",
            "geocoding-api.open-meteo.com",
            "api.met.no",
            "api.rainviewer.com",
            "feeds.meteoalarm.org",
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
    private static JLabel weatherSourceLabel;
    protected static DefaultTableModel forecastTableModel;
    protected static HourlyTimelineStrip hourlyTimelineStrip;
    protected static JTextField searchTextField;
    private static JPopupMenu locationSuggestionPopup;
    private static DefaultListModel<GeoSuggestion> locationSuggestionModel;
    private static JList<GeoSuggestion> locationSuggestionList;
    private static Timer locationSuggestionTimer;
    private static final AtomicInteger locationSuggestionRequestId = new AtomicInteger();
    private static LocationSuggestionRequestToken activeLocationSuggestionRequest;
    private static final int LOCATION_SUGGESTION_DEBOUNCE_MS = 120;
    protected static String customUserName = DEFAULT_USER_NAME;
    private static boolean lightTheme;
    protected static AppNavBar appNavBar;
    protected static JLabel heroIconLabel;
    protected static String uiFontFamily;
    protected static DailyForecastPanel dailyForecastPanel;
    protected static DailyDetailPanel dailyDetailPanel;
    private static DailyForecast[] displayedDailyForecasts = new DailyForecast[0];

    protected static TelemetryGaugeBox humidityMetricBox;
    protected static TelemetryGaugeBox uvMetricBox;
    protected static TelemetryGaugeBox pressureMetricBox;
    protected static TelemetryGaugeBox airQualityMetricBox;
    protected static AnalyticsForecastPanel analyticsForecastPanel;
    protected static RadarMapPanel radarMapPanel;
    private static WebcamPage webcamPage;
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
    private static volatile int stationWeatherCode = -1;
    private static volatile boolean stationIsNight;
    private static volatile String stationTimezone = "";
    private static volatile boolean[] forecastRowNightFlags = new boolean[0];
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
    private static volatile String weatherSourceStatusText = "Fonte meteo: in attesa dei dati";
    private static final String METEOALARM_ITALY_FEED =
            "https://feeds.meteoalarm.org/feeds/meteoalarm-legacy-atom-italy";
    private static final AtomicInteger weatherAlertRequestId = new AtomicInteger();
    private static WeatherAlertsPanel weatherAlertsPanel;
    private static StartupSplashWindow startupSplashWindow;
    private static Timer startupSplashTimeout;
    private static Timer startupSplashCloseTimer;
    private static long startupSplashStartedAtNanos;

    public static void main(String[] args) {
        customUserName = loadUserName();
        lightTheme = loadLightTheme();
        loadAppWallpaper(0);
        configureSwingThemeDefaults();

        SwingUtilities.invokeLater(() -> {
            rootWindow = new JFrame("MeteoBetti");
            URL appIconResource = MeteoBettiUltimateOS.class.getResource("/meteo-betti-icon.png");
            if (appIconResource != null) {
                rootWindow.setIconImage(new ImageIcon(appIconResource).getImage());
            }
            rootWindow.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            Rectangle screenBounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getMaximumWindowBounds();
            int minimumWindowWidth = Math.min(560, screenBounds.width);
            int windowWidth = Math.max(minimumWindowWidth, screenBounds.width / 2);
            int windowHeight = Math.max(1, screenBounds.height);
            rootWindow.setMinimumSize(new Dimension(
                    minimumWindowWidth, Math.min(600, windowHeight)));
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
                    if (!lightTheme) {
                        g2.setPaint(new GradientPaint(0, 0, new Color(8, 11, 17, 72),
                                0, getHeight(), new Color(8, 11, 17, 100)));
                        g2.fillRect(0, 0, getWidth(), getHeight());
                    }
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
            ThemeToggleButton themeToggle = new ThemeToggleButton();
            themeToggle.setSelected(lightTheme);
            themeToggle.addActionListener(event -> setLightTheme(themeToggle.isSelected()));
            discreetDropdownPanel.add(themeToggle);

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
            installLocationSuggestions();

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
            webcamPage = new WebcamPage();
            mainCardContainer.add(wrapPage(webcamPage), "WEBCAM");
            mainCardContainer.add(wrapPage(buildDailyDetailPage()), "DETTAGLIO_GIORNO");

            appNavBar = new AppNavBar();

            JPanel centralAssembly = new JPanel(new BorderLayout(0, 8));
            centralAssembly.setOpaque(false);
            centralAssembly.add(topContainer, BorderLayout.NORTH);
            centralAssembly.add(mainCardContainer, BorderLayout.CENTER);
            centralAssembly.add(appNavBar, BorderLayout.SOUTH);

            backgroundWrapperPanel.add(centralAssembly, BorderLayout.CENTER);
            rootWindow.add(backgroundWrapperPanel);
            applyCurrentTheme(rootWindow);
            new Timer(15 * 60 * 1000, e -> backgroundWrapperPanel.repaint()).start();
            new Timer(60 * 1000, e -> refreshDayNightAppearance()).start();

            beginStartupSplash(rootWindow);
            initPreciseLocationEngine(rootWindow);
            startupSplashWindow.setVisible(true);

            searchBtn.addActionListener(e -> performLocationSearch());
            searchTextField.addActionListener(e -> performLocationSearch());
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
        return new Font(uiFontFamily, style, Math.max(11, Math.round(size)));
    }

    private static void beginStartupSplash(JFrame owner) {
        startupSplashWindow = new StartupSplashWindow(owner);
        startupSplashStartedAtNanos = System.nanoTime();
        startupSplashTimeout = new Timer(60_000, event -> finishStartupSplash());
        startupSplashTimeout.setRepeats(false);
        startupSplashTimeout.start();
    }

    private static void finishStartupSplash() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(MeteoBettiUltimateOS::finishStartupSplash);
            return;
        }
        if (startupSplashWindow == null || startupSplashCloseTimer != null) return;
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                Math.max(0, System.nanoTime() - startupSplashStartedAtNanos));
        int remainingMillis = startupSplashRemainingMillis(elapsedMillis);
        if (remainingMillis > 0) {
            startupSplashCloseTimer = new Timer(remainingMillis, event -> disposeStartupSplash());
            startupSplashCloseTimer.setRepeats(false);
            startupSplashCloseTimer.start();
            return;
        }
        disposeStartupSplash();
    }

    static int startupSplashRemainingMillis(long elapsedMillis) {
        return (int) Math.max(0, 2_000 - Math.max(0, elapsedMillis));
    }

    private static void disposeStartupSplash() {
        if (startupSplashTimeout != null) {
            startupSplashTimeout.stop();
            startupSplashTimeout = null;
        }
        if (startupSplashCloseTimer != null) {
            startupSplashCloseTimer.stop();
            startupSplashCloseTimer = null;
        }
        if (startupSplashWindow != null) {
            StartupSplashWindow splash = startupSplashWindow;
            startupSplashWindow = null;
            splash.dispose();
        }
        if (rootWindow != null && !rootWindow.isVisible()) {
            rootWindow.setVisible(true);
        }
    }

    private static Color themePrimaryTextColor() {
        return lightTheme ? new Color(31, 43, 58) : new Color(244, 247, 250);
    }

    private static Color themeMutedTextColor() {
        return lightTheme ? new Color(77, 91, 108) : new Color(200, 210, 221);
    }

    private static void configureSwingThemeDefaults() {
        Color surface = lightTheme ? new Color(246, 249, 253) : new Color(22, 25, 30);
        Color text = themePrimaryTextColor();
        Color button = lightTheme ? new Color(231, 237, 244) : new Color(39, 43, 50);
        UIManager.put("OptionPane.background", surface);
        UIManager.put("Panel.background", surface);
        UIManager.put("OptionPane.foreground", text);
        UIManager.put("OptionPane.messageForeground", text);
        UIManager.put("OptionPane.buttonBackground", button);
        UIManager.put("OptionPane.buttonForeground", text);
        UIManager.put("OptionPane.messageFont", uiFont(Font.PLAIN, 13f));
        UIManager.put("OptionPane.buttonFont", uiFont(Font.BOLD, 12f));
        UIManager.put("Button.background", button);
        UIManager.put("Button.foreground", text);
        UIManager.put("TextField.background", lightTheme ? Color.WHITE : new Color(32, 36, 43));
        UIManager.put("TextField.foreground", text);
        UIManager.put("ComboBox.background", button);
        UIManager.put("ComboBox.foreground", text);
        UIManager.put("List.background", surface);
        UIManager.put("List.foreground", text);
        UIManager.put("List.selectionBackground",
                lightTheme ? new Color(205, 224, 243) : new Color(75, 83, 95));
        UIManager.put("List.selectionForeground", text);
        UIManager.put("ToolTip.background", button);
        UIManager.put("ToolTip.foreground", text);
    }

    private static boolean loadLightTheme() {
        try {
            return loadLightTheme(userPreferences());
        } catch (SecurityException | IllegalStateException exception) {
            System.err.println("Impossibile caricare il tema salvato: " + exception.getMessage());
            return false;
        }
    }

    static boolean loadLightTheme(Preferences preferences) {
        return preferences.getBoolean(LIGHT_THEME_PREFERENCE_KEY, false);
    }

    static void saveLightTheme(Preferences preferences, boolean enabled) throws BackingStoreException {
        preferences.putBoolean(LIGHT_THEME_PREFERENCE_KEY, enabled);
        preferences.flush();
    }

    private static void setLightTheme(boolean enabled) {
        lightTheme = enabled;
        configureSwingThemeDefaults();
        if (appNavBar != null) appNavBar.rebuild();
        if (dailyForecastPanel != null) dailyForecastPanel.updateForecasts(displayedDailyForecasts);
        if (hourlyTimelineStrip != null) hourlyTimelineStrip.refreshFromModel();
        if (dailyDetailPanel != null) dailyDetailPanel.refreshTheme();
        if (rootWindow != null) {
            applyCurrentTheme(rootWindow);
            rootWindow.repaint();
            rootWindow.revalidate();
        }
        try {
            saveLightTheme(userPreferences(), enabled);
        } catch (BackingStoreException | SecurityException | IllegalStateException exception) {
            System.err.println("Impossibile salvare il tema scelto: " + exception.getMessage());
            JOptionPane.showMessageDialog(rootWindow,
                    "Il tema è stato cambiato per questa sessione, ma non è stato possibile salvarlo.",
                    "Preferenza non salvata", JOptionPane.WARNING_MESSAGE);
        }
    }

    private static void applyCurrentTheme(Component component) {
        if (component instanceof JLabel) {
            JLabel label = (JLabel) component;
            Object alertSeverity = label.getClientProperty(ALERT_SEVERITY_PROPERTY);
            if (alertSeverity instanceof Integer) {
                int severity = (Integer) alertSeverity;
                label.setForeground(weatherAlertSeverityTextColor(severity));
                label.setOpaque(lightTheme && severity == 1);
                label.setBackground(new Color(255, 232, 128));
            } else if (!Boolean.TRUE.equals(
                    label.getClientProperty(PRESERVE_THEME_FOREGROUND_PROPERTY))) {
                label.setForeground(themePrimaryTextColor());
            }
        } else if (component instanceof AbstractButton
                || component instanceof JTextField || component instanceof JComboBox<?>) {
            component.setForeground(themePrimaryTextColor());
        }
        if (component instanceof JTextField) {
            component.setBackground(lightTheme ? new Color(239, 246, 253, 185)
                    : new Color(29, 33, 40, 220));
            ((JTextField) component).setCaretColor(lightTheme
                    ? new Color(35, 80, 126) : new Color(224, 231, 239));
        }
        if (component instanceof JButton && component.isOpaque()
                && !(component instanceof ThemeToggleButton)) {
            JButton button = (JButton) component;
            button.setBackground(lightTheme ? new Color(233, 241, 249, 190)
                    : new Color(16, 19, 24, 190));
            button.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(lightTheme
                            ? new Color(84, 104, 126, 105) : new Color(255, 255, 255, 78), 1, true),
                    new EmptyBorder(8, 14, 8, 14)));
        }
        if (component instanceof JComboBox<?>) {
            styleDropdownText((JComboBox<?>) component);
        }
        if (component == locationSuggestionList) {
            locationSuggestionList.setOpaque(false);
            locationSuggestionList.setBackground(lightTheme
                    ? new Color(239, 246, 253, 150) : new Color(24, 31, 40));
            locationSuggestionList.setForeground(themePrimaryTextColor());
            locationSuggestionList.setSelectionBackground(lightTheme
                    ? new Color(205, 224, 243) : new Color(54, 101, 145));
            locationSuggestionList.setSelectionForeground(themePrimaryTextColor());
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                applyCurrentTheme(child);
            }
        }
    }

    private static final class StartupSplashWindow extends JDialog {
        private final SplashWeatherOrbit weatherOrbit = new SplashWeatherOrbit();

        private StartupSplashWindow(JFrame owner) {
            super(owner, "MeteoBetti", Dialog.ModalityType.APPLICATION_MODAL);
            setUndecorated(true);
            setBackground(lightTheme ? new Color(226, 239, 251) : new Color(8, 9, 11));
            setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

            JPanel content = new JPanel(new BorderLayout()) {
                @Override
                protected void paintComponent(Graphics graphics) {
                    super.paintComponent(graphics);
                    Graphics2D g2 = (Graphics2D) graphics.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(getBackground());
                    g2.fillRect(0, 0, getWidth(), getHeight());
                    g2.setColor(lightTheme ? new Color(34, 58, 82, 30)
                            : new Color(0, 0, 0, 75));
                    g2.fillRoundRect(5, 7, getWidth() - 10, getHeight() - 14, 34, 34);
                    g2.setPaint(lightTheme
                            ? new GradientPaint(0, 0, new Color(255, 255, 255, 250),
                                    0, getHeight(), new Color(226, 239, 251, 248))
                            : new GradientPaint(0, 0, new Color(30, 31, 34),
                                    0, getHeight(), new Color(8, 9, 11)));
                    g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 2, 32, 32);
                    g2.setColor(lightTheme ? new Color(83, 132, 174, 65)
                            : new Color(196, 221, 246, 45));
                    g2.setStroke(new BasicStroke(0.7f));
                    g2.drawRoundRect(0, 0, getWidth() - 2, getHeight() - 3, 32, 32);
                    g2.dispose();
                }
            };
            content.setOpaque(true);
            content.setBackground(getBackground());
            content.setDoubleBuffered(true);
            content.setBorder(new EmptyBorder(25, 30, 22, 30));
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

            SplashWordmark wordmark = new SplashWordmark();
            wordmark.setAlignmentX(Component.CENTER_ALIGNMENT);

            JLabel subtitle = new JLabel("Elaborazione delle previsioni");
            subtitle.setFont(uiFont(Font.PLAIN, 12f));
            subtitle.setForeground(lightTheme ? new Color(74, 94, 114)
                    : new Color(213, 224, 237));
            subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);

            weatherOrbit.setAlignmentX(Component.CENTER_ALIGNMENT);
            content.add(Box.createVerticalGlue());
            content.add(weatherOrbit);
            content.add(Box.createVerticalStrut(10));
            content.add(wordmark);
            content.add(Box.createVerticalStrut(3));
            content.add(subtitle);
            content.add(Box.createVerticalGlue());

            setContentPane(content);
            setSize(440, 455);
            setLocationRelativeTo(null);
        }
    }

    private static final class SplashWordmark extends JComponent {
        private SplashWordmark() {
            setPreferredSize(new Dimension(350, 58));
            setMinimumSize(getPreferredSize());
            setMaximumSize(getPreferredSize());
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            Font wordmarkFont = uiFont(Font.BOLD, 36f);
            g2.setFont(wordmarkFont);
            FontMetrics metrics = g2.getFontMetrics();
            String firstPart = "METE";
            String lastPart = "BETTI";
            int sunCellWidth = metrics.charWidth('O');
            int wordmarkWidth = metrics.stringWidth(firstPart) + sunCellWidth
                    + metrics.stringWidth(lastPart);
            int startX = (getWidth() - wordmarkWidth) / 2;
            int baseline = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();

            g2.setColor(lightTheme ? new Color(38, 61, 85) : new Color(244, 248, 252));
            g2.drawString(firstPart, startX, baseline);
            int sunCenterX = startX + metrics.stringWidth(firstPart) + sunCellWidth / 2;
            int sunCenterY = baseline - metrics.getAscent() / 2;
            paintBrandSun(g2, sunCenterX, sunCenterY);
            g2.setColor(lightTheme ? new Color(24, 124, 172) : new Color(126, 217, 244));
            g2.drawString(lastPart, startX + metrics.stringWidth(firstPart) + sunCellWidth, baseline);
            g2.dispose();
        }

        private void paintBrandSun(Graphics2D graphics, int centerX, int centerY) {
            Color sunColor = lightTheme ? new Color(206, 145, 24) : new Color(255, 202, 92);
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setColor(sunColor);
            g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.fillOval(centerX - 6, centerY - 6, 12, 12);
            for (int ray = 0; ray < 8; ray++) {
                double angle = ray * Math.PI / 4;
                int innerX = (int) Math.round(centerX + Math.cos(angle) * 10);
                int innerY = (int) Math.round(centerY + Math.sin(angle) * 10);
                int outerX = (int) Math.round(centerX + Math.cos(angle) * 14);
                int outerY = (int) Math.round(centerY + Math.sin(angle) * 14);
                g2.drawLine(innerX, innerY, outerX, outerY);
            }
            g2.dispose();
        }
    }

    private static final class SplashWeatherOrbit extends JComponent {
        private static final int[] WEATHER_CODES = {0, 61, 3, 95, 71, 2};
        private static final Color[] DAY_COLORS = {
                new Color(226, 176, 47), new Color(62, 146, 205),
                new Color(73, 157, 208), new Color(72, 152, 205),
                new Color(78, 157, 211), new Color(213, 164, 50)
        };
        private static final Color[] NIGHT_COLORS = {
                new Color(153, 111, 22), new Color(40, 116, 171),
                new Color(48, 127, 181), new Color(49, 119, 170),
                new Color(51, 127, 183), new Color(137, 103, 26)
        };
        private static final double ORBIT_RADIUS = 70;
        private static final int ICON_SIZE = 35;
        private double rotationDegrees;
        private final Timer animationTimer;

        private SplashWeatherOrbit() {
            setPreferredSize(new Dimension(200, 200));
            setMinimumSize(getPreferredSize());
            setMaximumSize(getPreferredSize());
            setDoubleBuffered(true);
            animationTimer = new Timer(35, event -> {
                rotationDegrees = (rotationDegrees + 1.8) % 360;
                repaint();
            });
        }

        @Override
        public void addNotify() {
            super.addNotify();
            animationTimer.start();
        }

        @Override
        public void removeNotify() {
            animationTimer.stop();
            super.removeNotify();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.translate(getWidth() / 2.0, getHeight() / 2.0);
            g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            for (int i = 0; i < WEATHER_CODES.length; i++) {
                double startAngle = -90 + i * 60 + 15 + rotationDegrees;
                double endAngle = startAngle + 30;
                double endRadians = Math.toRadians(endAngle);
                Color[] palette = lightTheme ? DAY_COLORS : NIGHT_COLORS;
                g2.setColor(palette[i]);
                g2.draw(new java.awt.geom.Arc2D.Double(
                        -ORBIT_RADIUS, -ORBIT_RADIUS, ORBIT_RADIUS * 2, ORBIT_RADIUS * 2,
                        -startAngle, -30, java.awt.geom.Arc2D.OPEN));

                double tipX = Math.cos(endRadians) * ORBIT_RADIUS;
                double tipY = Math.sin(endRadians) * ORBIT_RADIUS;
                double tangentX = -Math.sin(endRadians);
                double tangentY = Math.cos(endRadians);
                double backX = tipX - tangentX * 8;
                double backY = tipY - tangentY * 8;
                double wing = 4;
                g2.drawLine((int) Math.round(tipX), (int) Math.round(tipY),
                        (int) Math.round(backX + tangentY * wing),
                        (int) Math.round(backY - tangentX * wing));
                g2.drawLine((int) Math.round(tipX), (int) Math.round(tipY),
                        (int) Math.round(backX - tangentY * wing),
                        (int) Math.round(backY + tangentX * wing));

                double iconAngle = Math.toRadians(-90 + i * 60 + rotationDegrees);
                int iconX = (int) Math.round(Math.cos(iconAngle) * ORBIT_RADIUS - ICON_SIZE / 2.0);
                int iconY = (int) Math.round(Math.sin(iconAngle) * ORBIT_RADIUS - ICON_SIZE / 2.0);
                if (i == 3) {
                    paintWindGlyph(g2, iconX, iconY, palette[i]);
                } else {
                    new WeatherIcon(WEATHER_CODES[i], ICON_SIZE, false)
                            .paintIcon(this, g2, iconX, iconY);
                }
            }
            g2.dispose();
        }

        private void paintWindGlyph(Graphics2D graphics, int x, int y, Color color) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.translate(x, y);
            g2.scale(ICON_SIZE / 24.0, ICON_SIZE / 24.0);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D wind = new Path2D.Double();
            wind.moveTo(3, 9);
            wind.curveTo(7, 9, 15, 9, 17, 7);
            wind.curveTo(20, 4, 15, 2, 14, 5);
            wind.moveTo(2, 13);
            wind.curveTo(8, 13, 16, 13, 20, 13);
            wind.curveTo(23, 13, 22, 17, 19, 17);
            wind.moveTo(5, 17);
            wind.curveTo(9, 17, 13, 17, 14, 19);
            wind.curveTo(15, 21, 12, 22, 11, 20);
            g2.draw(wind);
            g2.dispose();
        }
    }

    private static final class ThemeToggleButton extends JToggleButton {
        private ThemeToggleButton() {
            setText("Tema chiaro");
            setToolTipText("Attiva o disattiva la grafica chiara");
            setFont(uiFont(Font.BOLD, 12f));
            setPreferredSize(new Dimension(174, 38));
            setMinimumSize(getPreferredSize());
            setMaximumSize(getPreferredSize());
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setCursor(new Cursor(Cursor.HAND_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(lightTheme ? new Color(233, 241, 249, 188) : new Color(16, 19, 24, 190));
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
            g2.setColor(lightTheme ? new Color(82, 103, 126, 120) : new Color(255, 255, 255, 78));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
            g2.setFont(getFont());
            g2.setColor(themePrimaryTextColor());
            g2.drawString(getText(), 12, (getHeight() + g2.getFontMetrics().getAscent()
                    - g2.getFontMetrics().getDescent()) / 2);
            int trackX = getWidth() - 49;
            int trackY = (getHeight() - 20) / 2;
            g2.setColor(isSelected() ? new Color(68, 137, 199) : new Color(126, 137, 151));
            g2.fillRoundRect(trackX, trackY, 38, 20, 20, 20);
            g2.setColor(Color.WHITE);
            g2.fillOval(trackX + (isSelected() ? 20 : 2), trackY + 2, 16, 16);
            g2.dispose();
        }
    }

    private static void installLocationSuggestions() {
        locationSuggestionModel = new DefaultListModel<>();
        locationSuggestionList = new JList<>(locationSuggestionModel);
        locationSuggestionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        locationSuggestionList.setFocusable(false);
        locationSuggestionList.setFixedCellHeight(48);
        locationSuggestionList.setVisibleRowCount(6);
        locationSuggestionList.setFont(uiFont(Font.PLAIN, 13f));
        locationSuggestionList.setOpaque(false);
        locationSuggestionList.setBackground(new Color(24, 31, 40));
        locationSuggestionList.setForeground(new Color(238, 243, 249));
        locationSuggestionList.setSelectionBackground(new Color(54, 101, 145));
        locationSuggestionList.setSelectionForeground(Color.WHITE);
        locationSuggestionList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel item = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
                GeoSuggestion suggestion = (GeoSuggestion) value;
                item.setOpaque(isSelected);
                item.setBackground(isSelected
                        ? (lightTheme ? new Color(205, 224, 243) : new Color(79, 111, 145, 190))
                        : new Color(0, 0, 0, 0));
                item.setForeground(themePrimaryTextColor());
                item.setText("<html><b>" + escapeHtml(suggestion.name) + "</b><br><span style='color:"
                        + (lightTheme ? (isSelected ? "#263d55" : "#52677d")
                        : (isSelected ? "#e8f0f8" : "#b5c0ce")) + ";'>"
                        + escapeHtml(suggestion.subtitle()) + "</span></html>");
                item.setBorder(new EmptyBorder(5, 12, 5, 10));
                return item;
            }
        });
        locationSuggestionList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 1) {
                    int index = locationSuggestionList.locationToIndex(event.getPoint());
                    if (index >= 0) selectLocationSuggestion(index);
                }
            }
        });
        JScrollPane suggestionScroll = new JScrollPane(locationSuggestionList);
        suggestionScroll.setFocusable(false);
        suggestionScroll.setOpaque(false);
        suggestionScroll.getViewport().setOpaque(false);
        suggestionScroll.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
        suggestionScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        suggestionScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
        locationSuggestionPopup = new JPopupMenu() {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g2 = (Graphics2D) graphics.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int width = getWidth() - 1;
                int height = getHeight() - 1;
                g2.setPaint(lightTheme
                        ? new GradientPaint(0, 0, new Color(255, 255, 255, 246),
                                0, height, new Color(237, 244, 251, 246))
                        : new GradientPaint(0, 0, new Color(83, 89, 100, 220),
                                0, height, new Color(12, 15, 20, 238)));
                g2.fillRoundRect(0, 0, width, height, 22, 22);
                Shape previousClip = g2.getClip();
                g2.clip(new java.awt.geom.RoundRectangle2D.Float(1, 1,
                        Math.max(1, width - 2), Math.max(1, height - 2), 22, 22));
                g2.setPaint(new GradientPaint(0, 0,
                        new Color(255, 255, 255, lightTheme ? 118 : 34),
                        0, height / 2f, new Color(255, 255, 255, 0)));
                g2.fillRect(0, 0, width, height / 2);
                g2.setClip(previousClip);
                paintGlassOutline(g2, 0, 0, width, height, 22);
                g2.dispose();
                super.paintComponent(graphics);
            }
        };
        locationSuggestionPopup.setFocusable(false);
        locationSuggestionPopup.setRequestFocusEnabled(false);
        locationSuggestionPopup.setOpaque(false);
        locationSuggestionPopup.setBorder(new EmptyBorder(1, 1, 1, 1));
        locationSuggestionPopup.add(suggestionScroll);

        locationSuggestionTimer = new Timer(LOCATION_SUGGESTION_DEBOUNCE_MS, event -> requestLocationSuggestions(
                searchTextField.getText().trim()));
        locationSuggestionTimer.setRepeats(false);
        searchTextField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent event) {
                scheduleLocationSuggestions();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent event) {
                scheduleLocationSuggestions();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent event) {
                scheduleLocationSuggestions();
            }
        });
        searchTextField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_DOWN && locationSuggestionPopup.isVisible()) {
                    int next = Math.min(locationSuggestionList.getSelectedIndex() + 1,
                            locationSuggestionModel.size() - 1);
                    locationSuggestionList.setSelectedIndex(next);
                    locationSuggestionList.ensureIndexIsVisible(next);
                    event.consume();
                } else if (event.getKeyCode() == KeyEvent.VK_UP && locationSuggestionPopup.isVisible()) {
                    int current = locationSuggestionList.getSelectedIndex();
                    if (current <= 0) {
                        locationSuggestionList.clearSelection();
                    } else {
                        locationSuggestionList.setSelectedIndex(current - 1);
                    }
                    event.consume();
                } else if (event.getKeyCode() == KeyEvent.VK_ENTER
                        && shouldSelectLocationSuggestionOnEnter(
                                locationSuggestionPopup.isVisible(),
                                locationSuggestionList.getSelectedIndex(),
                                locationSuggestionModel.size())) {
                    selectLocationSuggestion(locationSuggestionList.getSelectedIndex());
                    event.consume();
                } else if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    hideLocationSuggestions();
                }
            }
        });
    }

    private static void scheduleLocationSuggestions() {
        if (locationSuggestionTimer == null) return;
        locationSuggestionRequestId.incrementAndGet();
        String query = searchTextField.getText().trim();
        hideLocationSuggestions();
        locationSuggestionList.clearSelection();
        locationSuggestionModel.clear();
        if (query.codePointCount(0, query.length()) < 2) {
            locationSuggestionTimer.stop();
            return;
        }
        locationSuggestionTimer.restart();
    }

    static boolean shouldSelectLocationSuggestionOnEnter(boolean popupVisible,
                                                         int selectedIndex,
                                                         int suggestionCount) {
        return popupVisible && selectedIndex >= 0 && selectedIndex < suggestionCount;
    }

    private static void performLocationSearch() {
        if (searchTextField == null) return;
        if (locationSuggestionTimer != null) locationSuggestionTimer.stop();
        boolean popupVisible = locationSuggestionPopup != null && locationSuggestionPopup.isVisible();
        int selectedIndex = locationSuggestionList == null
                ? -1 : locationSuggestionList.getSelectedIndex();
        int suggestionCount = locationSuggestionModel == null
                ? 0 : locationSuggestionModel.size();
        if (shouldSelectLocationSuggestionOnEnter(popupVisible, selectedIndex, suggestionCount)) {
            selectLocationSuggestion(selectedIndex);
            return;
        }
        locationSuggestionRequestId.incrementAndGet();
        hideLocationSuggestions();
        String query = searchTextField.getText().trim();
        if (!query.isEmpty()) executeAsyncWeatherFetch(rootWindow, query);
    }

    private static void requestLocationSuggestions(String query) {
        if (query == null || query.codePointCount(0, query.length()) < 2
                || !query.equals(searchTextField.getText().trim())) return;
        int requestId = locationSuggestionRequestId.incrementAndGet();
        LocationSuggestionRequestToken requestToken = new LocationSuggestionRequestToken();
        activeLocationSuggestionRequest = requestToken;
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String endpoint = "https://geocoding-api.open-meteo.com/v1/search?name=" + encoded
                + "&count=8&language=it&countryCode=IT&format=json";
        Thread request = new Thread(() -> {
            try {
                List<GeoSuggestion> suggestions = parseLocationSuggestions(
                        executeHttpGET(endpoint, requestToken, null, 2500, 2500));
                if (suggestions.isEmpty()) {
                    String worldwideEndpoint = "https://geocoding-api.open-meteo.com/v1/search?name="
                            + encoded + "&count=8&language=it&format=json";
                    suggestions = parseLocationSuggestions(
                            executeHttpGET(worldwideEndpoint, requestToken, null, 2500, 2500));
                }
                List<GeoSuggestion> finalSuggestions = suggestions;
                SwingUtilities.invokeLater(() -> {
                    if (requestId != locationSuggestionRequestId.get()
                            || !searchTextField.getText().trim().equals(query)) return;
                    if (activeLocationSuggestionRequest == requestToken) {
                        activeLocationSuggestionRequest = null;
                    }
                    showLocationSuggestions(finalSuggestions);
                });
            } catch (Exception exception) {
                if (!requestToken.isCancelled()) {
                    System.err.println("Suggerimenti località non disponibili: " + exception.getMessage());
                }
                SwingUtilities.invokeLater(() -> {
                    if (requestId == locationSuggestionRequestId.get()) {
                        if (activeLocationSuggestionRequest == requestToken) {
                            activeLocationSuggestionRequest = null;
                        }
                        hideLocationSuggestions();
                    }
                });
            }
        }, "meteobetti-location-suggestions");
        request.setDaemon(true);
        request.start();
    }

    private static void showLocationSuggestions(List<GeoSuggestion> suggestions) {
        locationSuggestionModel.clear();
        for (GeoSuggestion suggestion : suggestions) {
            locationSuggestionModel.addElement(suggestion);
        }
        if (suggestions.isEmpty() || !searchTextField.isShowing()) {
            hideLocationSuggestions();
            return;
        }
        locationSuggestionList.clearSelection();
        int rows = Math.min(6, suggestions.size());
        int width = Math.max(300, searchTextField.getWidth());
        locationSuggestionPopup.setPreferredSize(new Dimension(width, rows * 48));
        locationSuggestionPopup.show(searchTextField, 0, searchTextField.getHeight());
    }

    private static void hideLocationSuggestions() {
        locationSuggestionRequestId.incrementAndGet();
        if (locationSuggestionTimer != null) locationSuggestionTimer.stop();
        cancelLocationSuggestionRequest();
        if (locationSuggestionPopup != null) locationSuggestionPopup.setVisible(false);
    }

    private static void cancelLocationSuggestionRequest() {
        if (activeLocationSuggestionRequest == null) return;
        activeLocationSuggestionRequest.cancel();
        activeLocationSuggestionRequest = null;
    }

    private static void selectLocationSuggestion(int index) {
        if (index < 0 || index >= locationSuggestionModel.size()) return;
        GeoSuggestion suggestion = locationSuggestionModel.get(index);
        locationSuggestionRequestId.incrementAndGet();
        if (locationSuggestionTimer != null) locationSuggestionTimer.stop();
        hideLocationSuggestions();
        executeAsyncWeatherFetch(rootWindow, suggestion.toLocationFix());
    }

    static List<GeoSuggestion> parseLocationSuggestions(String json) {
        int resultsKey = json.indexOf("\"results\"");
        int arrayStart = resultsKey < 0 ? -1 : json.indexOf('[', resultsKey);
        if (arrayStart < 0) return List.of();
        List<GeoSuggestion> suggestions = new ArrayList<>();
        int objectStart = -1;
        int objectDepth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = arrayStart + 1; i < json.length(); i++) {
            char character = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    inString = false;
                }
                continue;
            }
            if (character == '"') {
                inString = true;
            } else if (character == '{') {
                if (objectDepth++ == 0) objectStart = i;
            } else if (character == '}' && objectDepth > 0) {
                objectDepth--;
                if (objectDepth == 0 && objectStart >= 0) {
                    GeoSuggestion suggestion = parseGeoSuggestion(
                            json.substring(objectStart, i + 1));
                    if (suggestion != null) suggestions.add(suggestion);
                    objectStart = -1;
                    if (suggestions.size() == 8) break;
                }
            } else if (character == ']' && objectDepth == 0) {
                break;
            }
        }
        return List.copyOf(suggestions);
    }

    private static GeoSuggestion parseGeoSuggestion(String object) {
        String name = parseJsonStringField(object, "name");
        String country = parseJsonStringField(object, "country");
        String admin1 = parseJsonStringField(object, "admin1");
        double latitude = parseJsonNumberField(object, "latitude");
        double longitude = parseJsonNumberField(object, "longitude");
        if (name.isBlank() || !Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            return null;
        }
        return new GeoSuggestion(name, admin1, country, latitude, longitude);
    }

    private static String parseJsonStringField(String json, String field) {
        String key = "\"" + field + "\"";
        int keyStart = json.indexOf(key);
        if (keyStart < 0) return "";
        int valueStart = json.indexOf(':', keyStart) + 1;
        if (valueStart == 0) return "";
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) valueStart++;
        if (valueStart >= json.length() || json.charAt(valueStart) != '"') return "";
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = valueStart + 1; i < json.length(); i++) {
            char character = json.charAt(i);
            if (escaped) {
                switch (character) {
                    case '"': value.append('"'); break;
                    case '\\': value.append('\\'); break;
                    case '/': value.append('/'); break;
                    case 'b': value.append('\b'); break;
                    case 'f': value.append('\f'); break;
                    case 'n': value.append('\n'); break;
                    case 'r': value.append('\r'); break;
                    case 't': value.append('\t'); break;
                    case 'u':
                        if (i + 4 >= json.length()) return "";
                        try {
                            value.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException exception) {
                            return "";
                        }
                        break;
                    default: return "";
                }
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '"') {
                return value.toString();
            } else {
                value.append(character);
            }
        }
        return "";
    }

    private static double parseJsonNumberField(String json, String field) {
        String key = "\"" + field + "\"";
        int keyStart = json.indexOf(key);
        if (keyStart < 0) return Double.NaN;
        int valueStart = json.indexOf(':', keyStart) + 1;
        if (valueStart == 0) return Double.NaN;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) valueStart++;
        int valueEnd = valueStart;
        while (valueEnd < json.length() && "-+.0123456789eE".indexOf(json.charAt(valueEnd)) >= 0) valueEnd++;
        try {
            return Double.parseDouble(json.substring(valueStart, valueEnd));
        } catch (NumberFormatException exception) {
            return Double.NaN;
        }
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
        g2.setColor(lightTheme ? new Color(49, 66, 85, 24) : new Color(0, 0, 0, 52));
        g2.fillRoundRect(3, 6, Math.max(1, width - 6), Math.max(1, height - 8),
                cardRadius, cardRadius);
        g2.setPaint(lightTheme
                ? new GradientPaint(0, 0, new Color(239, 246, 253, 218),
                        0, cardHeight, new Color(220, 232, 244, 202))
                : new GradientPaint(0, 0, new Color(83, 89, 100, 151),
                        0, cardHeight, new Color(18, 21, 27, 176)));
        g2.fillRoundRect(0, 0, width, cardHeight, cardRadius, cardRadius);

        Shape cardShape = new java.awt.geom.RoundRectangle2D.Float(
                1, 1, Math.max(1, width - 3), Math.max(1, cardHeight - 2), cardRadius, cardRadius);
        Shape previousClip = g2.getClip();
        g2.clip(cardShape);
        g2.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, lightTheme ? 78 : 32),
                0, cardHeight * 0.46f, new Color(255, 255, 255, 0)));
        g2.fillRect(0, 0, width, cardHeight);
        g2.setClip(previousClip);
    }

    private static void paintGlassOutline(Graphics2D graphics, int x, int y, int width, int height, int radius) {
        Stroke previousStroke = graphics.getStroke();
        graphics.setPaint(lightTheme
                ? new GradientPaint(x, y, new Color(74, 97, 121, 120),
                        x + width, y + height, new Color(126, 150, 175, 72))
                : new GradientPaint(x, y, new Color(242, 247, 255, 24),
                        x + width, y + height, new Color(196, 215, 239, 9)));
        graphics.setStroke(new BasicStroke(0.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.draw(new java.awt.geom.RoundRectangle2D.Float(
                x + 0.5f, y + 0.5f, Math.max(1, width - 1), Math.max(1, height - 1), radius, radius));
        graphics.setStroke(previousStroke);
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
        btn.setForeground(themePrimaryTextColor());
        btn.setBackground(new Color(255, 255, 255, 18));
        btn.setOpaque(false);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(true);
        btn.setFocusPainted(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.setBorder(BorderFactory.createCompoundBorder(new AbstractBorder() {
            @Override
            public void paintBorder(Component component, Graphics graphics,
                                    int x, int y, int width, int height) {
                if (!(component instanceof AbstractButton button)) return;
                Graphics2D g2 = (Graphics2D) graphics.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean pressed = button.getModel().isPressed();
                boolean hovered = button.getModel().isRollover();
                Color fill = lightTheme
                        ? new Color(239, 246, 253, pressed ? 230 : hovered ? 215 : 188)
                        : new Color(79, 91, 108, pressed ? 226 : hovered ? 203 : 176);
                Color outline = lightTheme
                        ? new Color(255, 255, 255, hovered ? 220 : 175)
                        : new Color(231, 240, 250, hovered ? 180 : 108);
                g2.setColor(fill);
                g2.fillRoundRect(x, y, width - 1, height - 1, 18, 18);
                g2.setColor(outline);
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(x, y, width - 1, height - 1, 18, 18);
                g2.setColor(new Color(255, 255, 255, hovered ? 90 : 48));
                g2.drawLine(x + 9, y + 1, x + width - 10, y + 1);
                g2.dispose();
            }

            @Override
            public Insets getBorderInsets(Component component) {
                return new Insets(8, 14, 8, 14);
            }
        }, new EmptyBorder(1, 1, 1, 1)));
    }

    private static <T> void styleDropdownText(JComboBox<T> selector) {
        Color textColor = lightTheme ? new Color(37, 51, 67) : new Color(226, 232, 240);
        Color menuColor = lightTheme ? new Color(246, 249, 253) : new Color(30, 35, 43);
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
                item.setBackground(isSelected
                        ? (lightTheme ? new Color(213, 229, 245) : new Color(70, 83, 101))
                        : menuColor);
                item.setBorder(new EmptyBorder(6, 10, 6, 10));
                return item;
            }
        });
        selector.setUI(new BasicComboBoxUI() {
            @Override
            protected JButton createArrowButton() {
                JButton arrow = new JButton("⌄");
                arrow.setFont(uiFont(Font.BOLD, 15f));
                arrow.setForeground(lightTheme ? new Color(63, 83, 105) : new Color(194, 205, 219));
                arrow.setBackground(lightTheme ? new Color(235, 241, 247, 220)
                        : new Color(30, 35, 43, 180));
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
                        BorderFactory.createLineBorder(lightTheme
                                ? new Color(99, 119, 141, 100) : new Color(150, 164, 182, 90), 1, true),
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
                g2.setPaint(lightTheme
                        ? new GradientPaint(0, 0, new Color(239, 246, 253, 178),
                                0, height, new Color(220, 232, 244, 158))
                        : new GradientPaint(0, 0, new Color(83, 89, 100, 156),
                                0, height, new Color(12, 15, 20, 184)));
                g2.fillRoundRect(0, 0, width, height, 28, 28);
                Shape previousClip = g2.getClip();
                g2.clip(new java.awt.geom.RoundRectangle2D.Float(1, 1, width - 2, height - 2, 28, 28));
                g2.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, lightTheme ? 72 : 42),
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
                thumbColor = lightTheme ? new Color(93, 112, 133, 165)
                        : new Color(132, 141, 150, 164);
                trackColor = lightTheme ? new Color(110, 129, 150, 36)
                        : new Color(232, 235, 238, 64);
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
                        ? (lightTheme ? new Color(70, 94, 119, 215)
                                : new Color(103, 115, 127, 210))
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
        heroIconLabel = new JLabel();
        heroIconLabel.setIcon(new WeatherIcon(0, 46));
        tempRow.add(mainTempDisplayLabel);
        tempRow.add(heroIconLabel);

        conditionTextLabel = new JLabel("Sincronizzazione in corso...");
        conditionTextLabel.setFont(uiFont(Font.PLAIN, 14f));
        conditionTextLabel.setForeground(new Color(211, 220, 230));
        conditionTextLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        weatherSourceLabel = new JLabel("Fonte meteo: in attesa dei dati");
        weatherSourceLabel.setFont(uiFont(Font.PLAIN, 10f));
        weatherSourceLabel.setForeground(new Color(193, 205, 218));
        weatherSourceLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        heroCard.add(heroCardTitle);
        heroCard.add(Box.createVerticalStrut(6));
        heroCard.add(mainLocationDisplayLabel);
        heroCard.add(Box.createVerticalStrut(4));
        heroCard.add(tempRow);
        heroCard.add(Box.createVerticalStrut(2));
        heroCard.add(conditionTextLabel);
        heroCard.add(Box.createVerticalStrut(4));
        heroCard.add(weatherSourceLabel);

        heroCard.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                String countryText = stationCountry == null || stationCountry.isBlank()
                        ? "non disponibile" : stationCountry;
                String locationText = stationCity + ", " + countryText;
                String temperatureText = Double.isFinite(stationTemp)
                        ? String.format(Locale.ITALIAN, "%.1f °C", stationTemp) : "non disponibile";
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
                String sourceNote = weatherSourceStatusText
                        + (weatherDataSourceName.contains("MET Norway")
                        ? " · MET Norway · CC BY 4.0" : "");
                openTelemetryPopup(rootWindow, "Meteo e posizione",
                        buildCurrentWeatherPopup(locationText, temperatureText, apparentText,
                                humidityText, pressureText, windText, uvText, sourceNote,
                                String.format(Locale.ITALIAN, "%.4f, %.4f", stationLat, stationLon)));
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

        humidityMetricBox = new TelemetryGaugeBox("Umidità", "--%", 0,
                () -> openMetricInfo("Umidità relativa",
                        String.format(Locale.ITALIAN, "%.0f%%", stationHumidity),
                        "Percentuale di umidità presente nell'aria rispetto alla quantità massima che può contenere alla temperatura attuale."));
        uvMetricBox = new TelemetryGaugeBox("UV oggi", "-- UV", 0,
                () -> openMetricInfo("Indice UV massimo previsto oggi",
                        String.format(Locale.ITALIAN, "%.1f UV", stationUvIndex),
                        "Valore massimo previsto nell'arco della giornata. Un indice più alto indica una maggiore intensità della radiazione ultravioletta."));
        pressureMetricBox = new TelemetryGaugeBox("Pressione", "-- hPa", 0,
                () -> openMetricInfo("Pressione atmosferica",
                        String.format(Locale.ITALIAN, "%.0f hPa", stationPressure),
                        "Pressione atmosferica rilevata per le coordinate selezionate."));
        airQualityMetricBox = new TelemetryGaugeBox("Qualità aria", "N/D", 0,
                () -> openMetricInfo("Qualità dell'aria",
                        Double.isNaN(stationEuropeanAqi) ? "Dato non disponibile"
                                : String.format(Locale.ITALIAN, "%.0f AQI · %s",
                                stationEuropeanAqi, airQualityCategory(stationEuropeanAqi)),
                        "Indice europeo AQI e concentrazioni PM2.5, PM10 e NO₂ stimati dal modello CAMS tramite Open-Meteo. Sono dati modellistici, non misure di una centralina locale."));

        metricsGrid.add(humidityMetricBox);
        metricsGrid.add(uvMetricBox);
        metricsGrid.add(pressureMetricBox);
        metricsGrid.add(airQualityMetricBox);
        weatherAlertsPanel = new WeatherAlertsPanel();
        weatherAlertsPanel.setAlignmentX(Component.CENTER_ALIGNMENT);

        dashboard.add(heroCard);
        dashboard.add(Box.createVerticalStrut(12));
        dashboard.add(timelineCard);
        dashboard.add(Box.createVerticalStrut(12));
        dashboard.add(metricsGrid);
        dashboard.add(Box.createVerticalStrut(12));
        dashboard.add(weatherAlertsPanel);
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
            displayedDailyForecasts = forecasts.clone();
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
                    ? String.format(Locale.ITALIAN, "%.0f%%", forecast.precipitationProbability) : "N/D";
            String wind = Double.isFinite(forecast.meanWindSpeed)
                    ? String.format(Locale.ITALIAN, "%.0f km/h", forecast.meanWindSpeed) : "N/D";
            String html = "<html><div style='text-align:left; color:"
                    + (lightTheme ? "#26384b" : "#f1f4f8")
                    + "; padding:2px; font-size:14px;'>" +
                    "<b>" + escapeHtml(forecast.shortDate()) + "</b><br>" +
                    "<b style='font-size:18px;'>" + average + "</b> &nbsp; Pioggia " + precipitation + "<br>" +
                    "<span style='color:" + (lightTheme ? "#586a7d" : "#c4ccd6")
                    + ";'>Vento medio " + wind + "</span></div></html>";
            JButton card = new JButton(html, new WeatherIcon(forecast.weatherCode, 27)) {
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
            card.setForeground(themePrimaryTextColor());
            card.setFocusPainted(false);
            card.setContentAreaFilled(false);
            card.setBorderPainted(false);
            card.setOpaque(false);
            card.setIconTextGap(9);
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
        private DailyForecast selectedForecast;

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
            temperatureLabel.setForeground(themePrimaryTextColor());
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
            hourlyForecast.setLayout(new FlowLayout(FlowLayout.LEFT, HourlyTimelineStrip.CELL_GAP, 0));
            hourlyForecast.setPreferredSize(new Dimension(
                    HourlyTimelineStrip.minimumStripWidth(), 118));
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
            selectedForecast = forecast;
            dateLabel.setText(capitalize(forecast.displayDate()));
            temperatureLabel.setText(Double.isFinite(forecast.meanTemperature)
                    ? String.format(Locale.ITALIAN, "%.1f°", forecast.meanTemperature) : "--°");
            conditionLabel.setText(forecast.weatherDescription());
            conditionLabel.setIcon(new WeatherIcon(forecast.weatherCode, 25));
            conditionLabel.setIconTextGap(8);
            String precipitation = Double.isFinite(forecast.precipitationProbability)
                    ? String.format(Locale.ITALIAN, "%.0f%%", forecast.precipitationProbability) : "--";
            String wind = Double.isFinite(forecast.meanWindSpeed)
                    ? String.format(Locale.ITALIAN, "%.1f km/h", forecast.meanWindSpeed) : "--";
            summaryLabel.setText("Pioggia " + precipitation + "  ·  Vento medio " + wind);
            hourlyForecast.updateFromForecast(forecast);
            charts.updateForecast(forecast);
        }

        void refreshTheme() {
            if (selectedForecast != null) showForecast(selectedForecast);
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
                if (data.length == 0 || point.y < 40 || point.y > getHeight() - 30) return -1;
                int left = 22;
                int right = Math.max(left + 1, getWidth() - 22);
                int closest = -1;
                double closestDistance = Double.POSITIVE_INFINITY;
                for (int i = 0; i < data.length; i++) {
                    if (!Double.isFinite(data[i])) continue;
                    double x = data.length == 1 ? (left + right) / 2.0
                            : left + (double) i * (right - left) / (data.length - 1);
                    double distance = Math.abs(point.x - x);
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
                g2.setColor(themePrimaryTextColor());
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
                    g2.setColor(themeMutedTextColor());
                    g2.drawString(value, getWidth() - g2.getFontMetrics().stringWidth(value) - 14, 22);
                }
                if (data.length == 0 || availableValues == 0) {
                    g2.setFont(uiFont(Font.PLAIN, 12f));
                    g2.setColor(themeMutedTextColor());
                    String unavailableMessage = analyticsNoDataMessage(
                            title, data.length, availableValues, weatherDataSourceName);
                    g2.drawString(unavailableMessage, 14, getHeight() / 2);
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
                double hoveredX = -1;
                if (hoveredIndex >= 0 && hoveredIndex < data.length
                        && Double.isFinite(data[hoveredIndex])) {
                    hoveredX = data.length == 1 ? (left + right) / 2.0
                            : left + (double) hoveredIndex * (right - left) / (data.length - 1);
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
                    g2.setStroke(new BasicStroke(1f));
                    g2.setColor(lightTheme ? new Color(77, 95, 114, 92)
                            : new Color(191, 204, 218, 56));
                    for (int gridline = 1; gridline <= 3; gridline++) {
                        int gridY = top + (bottom - top) * gridline / 4;
                        g2.drawLine(left, gridY, right, gridY);
                    }
                    g2.setColor(lightTheme ? new Color(68, 87, 107, 130)
                            : new Color(151, 170, 190, 90));
                    g2.drawLine(left, bottom, right, bottom);
                    g2.setColor(lightTheme ? accent.darker() : accent);
                    g2.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.draw(line);
                }

                if (hoveredX >= 0) {
                    g2.setColor(lightTheme ? new Color(40, 58, 76, 150)
                            : new Color(238, 244, 250, 190));
                    g2.setStroke(new BasicStroke(1.4f));
                    g2.drawLine((int) Math.round(hoveredX), top,
                            (int) Math.round(hoveredX), bottom);
                }

                g2.setFont(uiFont(Font.PLAIN, 10f));
                g2.setColor(themeMutedTextColor());
                for (int i = 0; i < data.length; i++) {
                    if (!Double.isFinite(data[i])) continue;
                    double x = data.length == 1 ? (left + right) / 2.0
                            : left + (double) i * (right - left) / (data.length - 1);
                    double y = bottom - ((data[i] - min) / range) * (bottom - top);
                    g2.setColor(Color.WHITE);
                    g2.setStroke(new BasicStroke(1.5f));
                    g2.fillOval((int) x - (i == hoveredIndex ? 7 : 4),
                            (int) y - (i == hoveredIndex ? 7 : 4),
                            i == hoveredIndex ? 14 : 8, i == hoveredIndex ? 14 : 8);
                    g2.setColor(lightTheme ? accent.darker() : accent);
                    g2.drawOval((int) x - (i == hoveredIndex ? 7 : 4),
                            (int) y - (i == hoveredIndex ? 7 : 4),
                            i == hoveredIndex ? 14 : 8, i == hoveredIndex ? 14 : 8);
                    g2.fillOval((int) x - 2, (int) y - 2, 4, 4);
                    if (i < dates.length) {
                        String day = LocalDate.parse(dates[i])
                                .format(DateTimeFormatter.ofPattern("EE", Locale.ITALIAN));
                        String label = day.substring(0, 1).toUpperCase(Locale.ITALIAN) + day.substring(1);
                        g2.setFont(uiFont(Font.BOLD, 10f));
                        g2.setColor(themeMutedTextColor());
                        int labelWidth = g2.getFontMetrics().stringWidth(label);
                        g2.drawString(label, (int) x - labelWidth / 2, getHeight() - 9);
                    }
                }
                if (hoveredIndex >= 0 && hoveredIndex < data.length
                        && Double.isFinite(data[hoveredIndex]) && hoveredX >= 0
                        && hoveredIndex < dates.length) {
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
                    int calloutY = top + 5;
                    g2.setColor(lightTheme ? new Color(255, 247, 220, 250)
                            : new Color(255, 220, 112, 248));
                    g2.fillRoundRect(calloutX, calloutY, calloutWidth, calloutHeight, 12, 12);
                    g2.setColor(new Color(30, 40, 52));
                    g2.drawString(callout, calloutX + 8, calloutY + metrics.getAscent() + 4);
                }
                g2.dispose();
            }

        }
    }

    static String analyticsNoDataMessage(String metric, int forecastCount,
                                         int availableValues, String dataSource) {
        if (forecastCount == 0) return "Previsioni giornaliere non caricate";
        if (availableValues > 0) return "";
        if (metric.equals("Probabilità pioggia") && "MET Norway".equals(dataSource)) {
            return "Probabilità pioggia non disponibile da MET Norway";
        }
        return "Dato non disponibile" + (dataSource == null || dataSource.isBlank()
                ? " dalla fonte meteo" : " da " + dataSource);
    }

    static final class WeatherAlert {
        final String event;
        final String area;
        final String severityLabel;
        final int severityLevel;
        final Instant onset;
        final Instant expires;

        private WeatherAlert(String event, String area, String severityLabel,
                             int severityLevel, Instant onset, Instant expires) {
            this.event = event;
            this.area = area;
            this.severityLabel = severityLabel;
            this.severityLevel = severityLevel;
            this.onset = onset;
            this.expires = expires;
        }
    }

    static List<WeatherAlert> parseWeatherAlerts(String atomXml, String region, Instant now)
            throws Exception {
        if (atomXml == null || atomXml.length() > MAX_HTTP_RESPONSE_CHARS) {
            throw new IllegalArgumentException("Feed allerte non valido o troppo grande");
        }
        if (region == null || region.isBlank()) return List.of();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override
            public void fatalError(org.xml.sax.SAXParseException exception)
                    throws org.xml.sax.SAXException {
                throw exception;
            }
        });
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        var document = builder.parse(new InputSource(new StringReader(atomXml)));
        NodeList entries = document.getElementsByTagNameNS("http://www.w3.org/2005/Atom", "entry");
        List<WeatherAlert> alerts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String normalizedRegion = normalizeAlertText(region);

        for (int index = 0; index < entries.getLength(); index++) {
            Element entry = (Element) entries.item(index);
            String area = firstXmlText(entry, "areaDesc");
            if (!normalizeAlertText(area).equals(normalizedRegion)) continue;
            if (!"actual".equalsIgnoreCase(firstXmlText(entry, "status"))) continue;
            String messageType = firstXmlText(entry, "message_type");
            if (!"alert".equalsIgnoreCase(messageType)
                    && !"update".equalsIgnoreCase(messageType)) continue;

            Instant expires = parseAlertInstant(firstXmlText(entry, "expires"));
            if (expires == null || !expires.isAfter(now)) continue;
            Instant onset = parseAlertInstant(firstXmlText(entry, "effective"));
            if (onset == null) onset = parseAlertInstant(firstXmlText(entry, "onset"));
            if (onset == null) onset = parseAlertInstant(firstXmlText(entry, "sent"));
            if (onset == null) onset = now;

            String event = firstXmlText(entry, "event");
            if (event.isBlank()) continue;
            String identifier = firstXmlText(entry, "identifier");
            String deduplicationKey = identifier.isBlank()
                    ? area + "|" + event + "|" + expires : identifier;
            if (!seen.add(deduplicationKey)) continue;

            String severity = firstXmlText(entry, "severity");
            int level = weatherAlertSeverityLevel(event, severity);
            if (level < 1) continue;
            alerts.add(new WeatherAlert(event, area, weatherAlertSeverityLabel(level),
                    level, onset, expires));
        }
        alerts.sort((first, second) -> {
            int severityOrder = Integer.compare(second.severityLevel, first.severityLevel);
            return severityOrder != 0 ? severityOrder : first.expires.compareTo(second.expires);
        });
        return List.copyOf(alerts);
    }

    private static String firstXmlText(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) return "";
        String text = nodes.item(0).getTextContent();
        return text == null ? "" : text.trim();
    }

    private static Instant parseAlertInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return java.time.OffsetDateTime.parse(value).toInstant();
        } catch (java.time.format.DateTimeParseException exception) {
            try {
                return Instant.parse(value);
            } catch (java.time.format.DateTimeParseException ignored) {
                return null;
            }
        }
    }

    private static String normalizeAlertText(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private static int weatherAlertSeverityLevel(String event, String severity) {
        String normalizedEvent = normalizeAlertText(event);
        if (normalizedEvent.contains("rosso") || normalizedEvent.contains("red")) return 3;
        if (normalizedEvent.contains("arancione") || normalizedEvent.contains("orange")) return 2;
        if (normalizedEvent.contains("giallo") || normalizedEvent.contains("yellow")) return 1;
        switch (normalizeAlertText(severity)) {
            case "extreme": return 3;
            case "severe": return 2;
            case "moderate": return 1;
            default: return 0;
        }
    }

    private static String weatherAlertSeverityLabel(int level) {
        if (level >= 3) return "ALLERTA ROSSA";
        if (level == 2) return "ALLERTA ARANCIONE";
        return "ALLERTA GIALLA";
    }

    private static Color weatherAlertSeverityColor(int level) {
        if (level >= 3) return lightTheme ? new Color(181, 38, 47) : new Color(220, 65, 73);
        if (level == 2) return lightTheme ? new Color(176, 86, 18) : new Color(237, 139, 48);
        return lightTheme ? new Color(255, 207, 32) : new Color(255, 211, 55);
    }

    private static Color weatherAlertSeverityTextColor(int level) {
        return lightTheme && level == 1 ? new Color(82, 61, 0) : weatherAlertSeverityColor(level);
    }

    private static void fetchWeatherAlerts(String country, String region) {
        int requestId = weatherAlertRequestId.incrementAndGet();
        String normalizedCountry = normalizeAlertText(country);
        if (!normalizedCountry.equals("italia") && !normalizedCountry.equals("italy")) {
            weatherAlertsPanel.showUnavailable("Bollettini regionali disponibili solo per l'Italia.");
            return;
        }
        if (region == null || region.isBlank()) {
            weatherAlertsPanel.showUnavailable(
                    "Regione non determinata per questa posizione. Cerca una città per consultare le allerte.");
            return;
        }
        weatherAlertsPanel.showLoading(region);
        Thread request = new Thread(() -> {
            try {
                String feed = executeHttpGET(METEOALARM_ITALY_FEED, null,
                        "MeteoBetti/1.0 (weather alerts)");
                List<WeatherAlert> alerts = parseWeatherAlerts(feed, region, Instant.now());
                SwingUtilities.invokeLater(() -> {
                    if (requestId == weatherAlertRequestId.get()) {
                        weatherAlertsPanel.showAlerts(region, alerts);
                    }
                });
            } catch (Exception exception) {
                System.err.println("Allerte MeteoAlarm non disponibili: " + exception.getMessage());
                SwingUtilities.invokeLater(() -> {
                    if (requestId == weatherAlertRequestId.get()) {
                        weatherAlertsPanel.showUnavailable(
                                "Non è stato possibile aggiornare le allerte. Riprova più tardi.");
                    }
                });
            }
        }, "meteobetti-weather-alerts");
        request.setDaemon(true);
        request.start();
    }

    private static final class WeatherAlertsPanel extends JPanel {
        private final JLabel titleLabel = new JLabel("ALLERTE SEGNALATE");
        private final JPanel alertList = new JPanel();
        private final JLabel statusLabel = new JLabel("Cerca una località per consultare le allerte.");
        private final JLabel attributionLabel = new JLabel(
                "<html>Fonte: <b>MeteoAlarm</b> · servizi meteo nazionali · "
                        + "CC BY 4.0: https://creativecommons.org/licenses/by/4.0/<br>"
                        + "Non sostituisce le allerte ufficiali della Protezione Civile.</html>");

        private WeatherAlertsPanel() {
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(new EmptyBorder(15, 16, 13, 16));
            setMaximumSize(new Dimension(520, Integer.MAX_VALUE));

            titleLabel.setFont(uiFont(Font.BOLD, 11f));
            titleLabel.setForeground(themePrimaryTextColor());
            titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            alertList.setOpaque(false);
            alertList.setLayout(new BoxLayout(alertList, BoxLayout.Y_AXIS));
            alertList.setAlignmentX(Component.LEFT_ALIGNMENT);
            statusLabel.setFont(uiFont(Font.PLAIN, 12f));
            statusLabel.setForeground(themeMutedTextColor());
            statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            attributionLabel.setFont(uiFont(Font.PLAIN, 10f));
            attributionLabel.setForeground(themeMutedTextColor());
            attributionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

            add(titleLabel);
            add(Box.createVerticalStrut(8));
            add(statusLabel);
            add(alertList);
            add(Box.createVerticalStrut(8));
            add(attributionLabel);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            paintFrostedCard(g2, getWidth(), getHeight(), 22);
            g2.dispose();
        }

        private void showLoading(String region) {
            titleLabel.setText("ALLERTE SEGNALATE · " + region);
            statusLabel.setText("Controllo gli avvisi pubblicati per la regione...");
            alertList.removeAll();
            revalidate();
            repaint();
        }

        private void showUnavailable(String message) {
            titleLabel.setText("ALLERTE SEGNALATE");
            statusLabel.setText(message);
            alertList.removeAll();
            revalidate();
            repaint();
        }

        private void showAlerts(String region, List<WeatherAlert> alerts) {
            titleLabel.setText("ALLERTE SEGNALATE · " + region);
            alertList.removeAll();
            if (alerts.isEmpty()) {
                statusLabel.setText("Nessuna allerta attiva segnalata nella regione.");
            } else {
                statusLabel.setText(alerts.size() == 1
                        ? "È stata segnalata 1 allerta:"
                        : "Sono state segnalate " + alerts.size() + " allerte:");
                for (WeatherAlert alert : alerts) {
                    alertList.add(Box.createVerticalStrut(6));
                    alertList.add(new WeatherAlertCard(alert));
                }
            }
            revalidate();
            repaint();
        }
    }

    private static final class WeatherAlertCard extends JPanel {
        private final WeatherAlert alert;

        private WeatherAlertCard(WeatherAlert alert) {
            this.alert = alert;
            setOpaque(false);
            setLayout(new BorderLayout(10, 0));
            setBorder(new EmptyBorder(8, 11, 8, 8));
            JLabel levelLabel = new JLabel(alert.severityLabel);
            levelLabel.setFont(uiFont(Font.BOLD, 9f));
            levelLabel.setForeground(weatherAlertSeverityTextColor(alert.severityLevel));
            levelLabel.putClientProperty(ALERT_SEVERITY_PROPERTY, alert.severityLevel);
            levelLabel.setOpaque(lightTheme && alert.severityLevel == 1);
            if (lightTheme && alert.severityLevel == 1) {
                levelLabel.setBackground(new Color(255, 232, 128));
                levelLabel.setBorder(new EmptyBorder(2, 5, 2, 5));
            }
            JLabel eventLabel = new JLabel(escapeHtml(weatherAlertDisplayName(alert.event)));
            eventLabel.setFont(uiFont(Font.BOLD, 12f));
            eventLabel.setForeground(themePrimaryTextColor());
            JLabel detailsLabel = new JLabel(formatAlertTime(alert.onset, alert.expires));
            detailsLabel.setFont(uiFont(Font.PLAIN, 10f));
            detailsLabel.setForeground(themeMutedTextColor());

            JPanel text = new JPanel();
            text.setOpaque(false);
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
            text.add(levelLabel);
            text.add(Box.createVerticalStrut(3));
            text.add(eventLabel);
            text.add(Box.createVerticalStrut(2));
            text.add(detailsLabel);
            add(text, BorderLayout.CENTER);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(lightTheme ? new Color(255, 255, 255, 180)
                    : new Color(255, 255, 255, 17));
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 13, 13);
            g2.setColor(weatherAlertSeverityColor(alert.severityLevel));
            g2.fillRoundRect(0, 2, 5, getHeight() - 5, 5, 5);
            g2.dispose();
        }
    }

    private static String formatAlertTime(Instant onset, Instant expires) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale.ITALIAN)
                .withZone(ZoneId.systemDefault());
        String start = onset.isAfter(Instant.now()) ? "Dalle " + formatter.format(onset) : "Attiva";
        return start + " · fino al " + formatter.format(expires);
    }

    private static String weatherAlertDisplayName(String event) {
        String normalized = normalizeAlertText(event);
        if (normalized.contains("rain") || normalized.contains("pioggia")) return "Pioggia";
        if (normalized.contains("thunder") || normalized.contains("temporale")) return "Temporali";
        if (normalized.contains("wind") || normalized.contains("vento")) return "Vento";
        if (normalized.contains("snow") || normalized.contains("neve")) return "Neve";
        if (normalized.contains("fog") || normalized.contains("nebbia")) return "Nebbia";
        if (normalized.contains("flood") || normalized.contains("alluvione")) return "Alluvione";
        if (normalized.contains("heat") || normalized.contains("caldo")
                || normalized.contains("high temperature")) return "Caldo intenso";
        if (normalized.contains("cold") || normalized.contains("freddo")) return "Freddo";
        if (normalized.contains("air quality") || normalized.contains("air pollution")
                || normalized.contains("qualita aria")) return "Qualità dell'aria";
        if (normalized.contains("fire") || normalized.contains("incendio")) return "Incendi";
        return event;
    }

    static URI buildWindyWebcamsUri(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
                || !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Coordinate non valide per la mappa webcam");
        }
        return URI.create(String.format(Locale.ROOT,
                "https://www.windy.com/it/-Webcam/webcams?%.4f,%.4f,8",
                latitude, longitude));
    }

    private static final class WebcamPage extends JPanel {
        private final JLabel locationLabel = new JLabel("Cerca una località per iniziare");
        private final JLabel statusLabel = new JLabel("La mappa si apre sul sito ufficiale di Windy.");
        private final JButton openButton = new JButton("Apri webcam vicine su Windy");
        private double latitude = Double.NaN;
        private double longitude = Double.NaN;

        private WebcamPage() {
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            JLabel title = createSectionTitle("Webcam della zona");
            title.setAlignmentX(Component.CENTER_ALIGNMENT);
            add(title);
            add(Box.createVerticalStrut(8));

            JPanel card = createLiquidGlassCardPanel();
            card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
            card.setBorder(new EmptyBorder(24, 24, 24, 24));
            card.setMaximumSize(new Dimension(500, 300));
            card.setAlignmentX(Component.CENTER_ALIGNMENT);

            NavGlyph cameraGlyph = new NavGlyph("webcam", true);
            cameraGlyph.setAlignmentX(Component.CENTER_ALIGNMENT);
            locationLabel.setFont(uiFont(Font.BOLD, 17f));
            locationLabel.setForeground(themePrimaryTextColor());
            locationLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

            JLabel explanation = new JLabel("<html><div style='text-align:center;width:390px'>"
                    + "Guarda le webcam disponibili vicino alla località selezionata. "
                    + "Si aprirà la mappa di Windy nel browser, senza copiare o salvare "
                    + "le immagini nell'app.</div></html>");
            explanation.setFont(uiFont(Font.PLAIN, 13f));
            explanation.setForeground(themeMutedTextColor());
            explanation.setAlignmentX(Component.CENTER_ALIGNMENT);

            styleDiscreetButton(openButton);
            openButton.setFont(uiFont(Font.BOLD, 13f));
            openButton.setAlignmentX(Component.CENTER_ALIGNMENT);
            openButton.setEnabled(false);
            openButton.addActionListener(event -> openNearbyWebcams());

            statusLabel.setFont(uiFont(Font.PLAIN, 11f));
            statusLabel.setForeground(themeMutedTextColor());
            statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

            JLabel rightsNote = new JLabel("<html><div style='text-align:center;width:390px'>"
                    + "Le immagini restano sul sito dei rispettivi gestori e sono soggette "
                    + "ai loro diritti e condizioni. Mappa: © OpenStreetMap contributors.</div></html>");
            rightsNote.setFont(uiFont(Font.PLAIN, 10f));
            rightsNote.setForeground(themeMutedTextColor());
            rightsNote.setAlignmentX(Component.CENTER_ALIGNMENT);

            card.add(cameraGlyph);
            card.add(Box.createVerticalStrut(10));
            card.add(locationLabel);
            card.add(Box.createVerticalStrut(10));
            card.add(explanation);
            card.add(Box.createVerticalStrut(16));
            card.add(openButton);
            card.add(Box.createVerticalStrut(8));
            card.add(statusLabel);
            card.add(Box.createVerticalStrut(14));
            card.add(rightsNote);
            add(card);
        }

        private void updateLocation(String city, String country, double newLatitude,
                                    double newLongitude) {
            latitude = newLatitude;
            longitude = newLongitude;
            boolean hasLocation = Double.isFinite(latitude) && latitude >= -90 && latitude <= 90
                    && Double.isFinite(longitude) && longitude >= -180 && longitude <= 180;
            locationLabel.setText(hasLocation
                    ? "<html><b>" + escapeHtml(city)
                    + (country == null || country.isBlank() ? "" : ", " + escapeHtml(country))
                    + "</b></html>"
                    : "Cerca una località per iniziare");
            openButton.setEnabled(hasLocation);
            statusLabel.setText(hasLocation
                    ? "Webcam e immagini sono fornite e mostrate da Windy."
                    : "La mappa si apre sul sito ufficiale di Windy.");
        }

        private void openNearbyWebcams() {
            if (!openButton.isEnabled()) return;
            try {
                if (!Desktop.isDesktopSupported()
                        || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    throw new UnsupportedOperationException("Apertura del browser non supportata");
                }
                Desktop.getDesktop().browse(buildWindyWebcamsUri(latitude, longitude));
                statusLabel.setText("Mappa webcam aperta nel browser.");
            } catch (IOException | UnsupportedOperationException | SecurityException ex) {
                System.err.println("Impossibile aprire la mappa webcam: " + ex.getMessage());
                statusLabel.setText("Impossibile aprire il browser. Riprova o controlla le impostazioni.");
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
        source.setFont(uiFont(Font.PLAIN, 10f));
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
        modelNote.setFont(uiFont(Font.ITALIC, 12f));
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

    static void setForecastDates(JComboBox<ForecastDateOption> selector, String[] dates) {
        setForecastDates(selector, dates, false);
    }

    static void setForecastDates(JComboBox<ForecastDateOption> selector, String[] dates,
                                 boolean selectFirstDate) {
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
                if (!selectFirstDate && selectedDateAvailable) {
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

    static final class ForecastDateOption {
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
            g2.setColor(lightTheme ? new Color(255, 255, 255, 224)
                    : new Color(14, 17, 22, 194));
            g2.fillRoundRect(x, y, width, height, 15, 15);
            g2.setColor(lightTheme ? new Color(82, 103, 126, 105)
                    : new Color(255, 255, 255, 72));
            g2.drawRoundRect(x, y, width - 1, height - 1, 15, 15);

            g2.setFont(uiFont(Font.BOLD, 11f));
            g2.setColor(themePrimaryTextColor());
            g2.drawString(names[series], x + 10, y + 15);
            double[] data = series < visibleValues.length ? visibleValues[series] : new double[0];
            int latestIndex = lastFiniteIndex(data);
            if (latestIndex >= 0) {
                String latest = String.format(Locale.ITALIAN, "%.1f", data[latestIndex]);
                g2.setFont(uiFont(Font.BOLD, 11f));
                g2.setColor(themeMutedTextColor());
                int labelWidth = g2.getFontMetrics().stringWidth(latest);
                g2.drawString(latest, x + width - labelWidth - 10, y + 15);
            }

            int left = x + 10;
            int top = y + 24;
            int plotWidth = width - 20;
            int plotHeight = height - 39;
            g2.setStroke(new BasicStroke(1f));
            g2.setColor(lightTheme ? new Color(87, 105, 124, 115)
                    : new Color(220, 228, 237, 80));
            for (int gridline = 0; gridline <= 3; gridline++) {
                int gridY = top + plotHeight * gridline / 3;
                g2.drawLine(left, gridY, left + plotWidth, gridY);
            }
            if (data.length < 2 || latestIndex < 0) {
                g2.setFont(uiFont(Font.PLAIN, 10f));
                g2.setColor(themeMutedTextColor());
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
            g2.setColor(lightTheme ? new Color(35, 101, 157)
                    : new Color(208, 218, 229));
            g2.setStroke(new BasicStroke(2.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(line);
            if (visibleTimes.length > 1) {
                g2.setFont(uiFont(Font.PLAIN, 10f));
                g2.setColor(themeMutedTextColor());
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

    private interface HttpRequestToken {
        void register(HttpURLConnection connection) throws InterruptedException;

        void unregister(HttpURLConnection connection);

        void check() throws InterruptedException;

        boolean isCancelled();
    }

    private static final class LocationSuggestionRequestToken implements HttpRequestToken {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final Set<HttpURLConnection> activeConnections = ConcurrentHashMap.newKeySet();

        @Override
        public synchronized void register(HttpURLConnection connection) throws InterruptedException {
            check();
            activeConnections.add(connection);
        }

        @Override
        public synchronized void unregister(HttpURLConnection connection) {
            activeConnections.remove(connection);
        }

        synchronized void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;
            for (HttpURLConnection connection : activeConnections) {
                connection.disconnect();
            }
            activeConnections.clear();
        }

        @Override
        public void check() throws InterruptedException {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Richiesta suggerimenti annullata");
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    private static final class RadarLoadToken implements HttpRequestToken {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final Set<HttpURLConnection> activeConnections = ConcurrentHashMap.newKeySet();
        private final Set<Future<?>> activeTileTasks = ConcurrentHashMap.newKeySet();

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public synchronized void register(HttpURLConnection connection) throws InterruptedException {
            check();
            activeConnections.add(connection);
        }

        @Override
        public synchronized void unregister(HttpURLConnection connection) {
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

        @Override
        public void check() throws InterruptedException {
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
            layerSelector.setFont(uiFont(Font.PLAIN, 12f));
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

            statusLabel.setFont(uiFont(Font.PLAIN, 12f));
            statusLabel.setForeground(new Color(46, 64, 80));
            JLabel navigationHint = createAirDataLabel(
                    "Mappa fissa · raggio " + RADAR_RADIUS_KM + " km dalla località cercata");
            navigationHint.setFont(uiFont(Font.PLAIN, 11f));
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
            g2.setFont(uiFont(Font.PLAIN, 10f));
            g2.setColor(themePrimaryTextColor());
            g2.drawString(layer.legend, 2, 13);

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

            g2.setFont(uiFont(Font.PLAIN, 10f));
            g2.setColor(themeMutedTextColor());
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
                    int right = (int) Math.round(RADAR_MAP_SIZE / 2.0
                            + ((tile.offsetX + 1) * 256 - fractionX) * tileScale);
                    int bottom = (int) Math.round(RADAR_MAP_SIZE / 2.0
                            + ((tile.offsetY + 1) * 256 - fractionY) * tileScale);
                    int tileWidth = Math.max(1, right - x + 1);
                    int tileHeight = Math.max(1, bottom - y + 1);
                    if (tile.baseMap != null) {
                        g2.drawImage(tile.baseMap, x, y, tileWidth, tileHeight, null);
                        baseMapTileCount++;
                    }
                    if (tile.radar != null) {
                        g2.drawImage(tile.radar, x, y, tileWidth, tileHeight, null);
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
        return Math.max(3, Math.min(MAX_RADAR_TILE_ZOOM, zoom));
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
                g2.setColor(new Color(48, 63, 79));
                g2.setFont(uiFont(Font.PLAIN, 13f));
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

            g2.setColor(themePrimaryTextColor());
            g2.setFont(uiFont(Font.BOLD, 13f));
            g2.drawString("Temperatura · prossime ore", 18, 26);

            if (graphValues.length < 2) {
            g2.setColor(themeMutedTextColor());
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
            g2.setColor(lightTheme ? new Color(98, 119, 142, 125)
                    : new Color(220, 228, 237, 48));
            for (int gridline = 1; gridline <= 3; gridline++) {
                int gridY = pY + h * gridline / 4;
                g2.drawLine(pX, gridY, pX + w, gridY);
            }
            g2.setColor(lightTheme ? new Color(35, 101, 157)
                    : new Color(54, 132, 196));
            g2.setStroke(new BasicStroke(3.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(path);

            int labelInterval = Math.max(1, (graphValues.length - 1) / 5);
            for (int i = 0; i < graphValues.length; i++) {
                double val = graphValues[i];
                double x = pointX[i];
                double y = pointY[i];
                g2.setColor(lightTheme ? new Color(35, 101, 157)
                        : new Color(54, 132, 196));
                g2.fillOval((int) x - 2, (int) y - 2, 5, 5);
                if (i % labelInterval != 0 && i != graphValues.length - 1) continue;
                g2.setColor(themePrimaryTextColor());
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

            SwingUtilities.invokeLater(() -> {
                mainLocationDisplayLabel.setText(
                        "Posizione non disponibile · controlla i permessi di localizzazione o cerca una città.");
                finishStartupSplash();
            });
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

        Path responseFile = java.nio.file.Files.createTempFile("meteobetti-location-", ".txt",
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        try {
            Process process = new ProcessBuilder("/usr/bin/open", "-n", "-W",
                    appBundle.resolve("Contents/Helpers/LocationAgent.app").toString(),
                    "--args", responseFile.toString()).start();
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Richiesta di posizione scaduta");
            }
            if (process.exitValue() != 0) {
                String launcherError = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                        .trim();
                throw new IllegalStateException(launcherError.isEmpty()
                        ? "Impossibile avviare il servizio di localizzazione macOS" : launcherError);
            }
            String response = java.nio.file.Files.readString(responseFile, StandardCharsets.UTF_8).trim();
            if (response.startsWith("ERROR|")) {
                throw new IllegalStateException(response.substring("ERROR|".length()));
            }
            if (!response.startsWith("OK|")) {
                throw new IllegalStateException("Il servizio di localizzazione macOS non ha restituito dati");
            }
            String output = response.substring("OK|".length());
            String[] values = output.split("\\|", 4);
            if (values.length < 2) {
                throw new IllegalStateException("Risposta non valida dall'helper di localizzazione");
            }

            double latitude = Double.parseDouble(values[0]);
            double longitude = Double.parseDouble(values[1]);
            String city = values.length > 2 ? values[2] : "";
            String country = values.length > 3 ? values[3] : "";
            return new LocationFix(latitude, longitude, city, country);
        } finally {
            java.nio.file.Files.deleteIfExists(responseFile);
        }
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
        private final String admin1;

        private LocationFix(double latitude, double longitude, String city, String country) {
            this(latitude, longitude, city, country, "");
        }

        private LocationFix(double latitude, double longitude, String city, String country,
                            String admin1) {
            if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
                    || !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
                throw new IllegalArgumentException("Coordinate geografiche non valide");
            }
            this.latitude = latitude;
            this.longitude = longitude;
            this.city = city == null ? "" : city;
            this.country = country == null ? "" : country;
            this.admin1 = admin1 == null ? "" : admin1;
        }
    }

    static final class GeoSuggestion {
        final String name;
        final String admin1;
        final String country;
        final double latitude;
        final double longitude;

        private GeoSuggestion(String name, String admin1, String country,
                              double latitude, double longitude) {
            this.name = name;
            this.admin1 = admin1;
            this.country = country;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        String subtitle() {
            if (!admin1.isBlank() && !country.isBlank()) return admin1 + " · " + country;
            return admin1.isBlank() ? country : admin1;
        }

        String displayName() {
            return admin1.isBlank() ? name : name + ", " + admin1;
        }

        private LocationFix toLocationFix() {
            return new LocationFix(latitude, longitude, displayName(), country, admin1);
        }
    }

    static String wallpaperFileName(int weatherCode) {
        if (weatherCode == 0) return "sfondo_sole.jpg";
        if (isRainCode(weatherCode) || isThunderCode(weatherCode)) {
            return "sfondo_pioggia.jpg";
        }
        if ((weatherCode >= 1 && weatherCode <= 3)
                || (weatherCode >= 45 && weatherCode <= 48) || weatherCode < 0) {
            return "sfondo_nuvoloso.jpg";
        }
        return "sfondo.jpg";
    }

    static String wallpaperFileName(int weatherCode, boolean night) {
        String daytimeFileName = wallpaperFileName(weatherCode);
        if (!night) return daytimeFileName;
        if (daytimeFileName.equals("sfondo.jpg")) return "sfondo_notte.jpg";
        return daytimeFileName.replace("sfondo_", "sfondo_notte_");
    }

    private static void loadAppWallpaper(int weatherCode) {
        loadAppWallpaper(weatherCode, false);
    }

    private static void loadAppWallpaper(int weatherCode, boolean night) {
        String daytimeFileName = wallpaperFileName(weatherCode);
        String[] candidates = night
                ? new String[]{wallpaperFileName(weatherCode, true), daytimeFileName}
                : new String[]{daytimeFileName};
        for (String fileName : candidates) {
            try {
                Image wallpaper = readWallpaperImage(fileName);
                if (wallpaper != null) {
                    currentWallpaperImage = wallpaper;
                    return;
                }
            } catch (IOException ex) {
                System.err.println("Impossibile caricare lo sfondo " + fileName + ": " + ex.getMessage());
            }
        }

        try {
            File legacyWallpaper = new File("sfondo.jpg");
            if (!legacyWallpaper.isFile()) legacyWallpaper = new File("sfondo.jpeg");
            currentWallpaperImage = legacyWallpaper.isFile() ? ImageIO.read(legacyWallpaper) : null;
        } catch (Exception ex) {
            System.err.println("Impossibile caricare lo sfondo predefinito: " + ex.getMessage());
            currentWallpaperImage = null;
        }
    }

    private static Image readWallpaperImage(String fileName) throws IOException {
        File selectedFile = new File(fileName);
        if (selectedFile.isFile()) return ImageIO.read(selectedFile);
        URL wallpaperResource = MeteoBettiUltimateOS.class.getResource("/" + fileName);
        return wallpaperResource == null ? null : ImageIO.read(wallpaperResource);
    }

    static boolean isNightAt(String dateTime, String timezone, double latitude, double longitude) {
        if (dateTime == null || dateTime.length() < 16
                || !Double.isFinite(latitude) || latitude < -90 || latitude > 90
                || !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            return false;
        }
        try {
            java.time.LocalDateTime localDateTime =
                    java.time.LocalDateTime.parse(dateTime.substring(0, 16));
            ZoneId zone;
            try {
                zone = timezone == null || timezone.isBlank()
                        ? ZoneId.systemDefault() : ZoneId.of(timezone);
            } catch (java.time.DateTimeException exception) {
                zone = ZoneId.systemDefault();
            }
            double zoneOffsetHours = zone.getRules().getOffset(localDateTime).getTotalSeconds() / 3600.0;
            int dayOfYear = localDateTime.getDayOfYear();
            double fractionalHour = localDateTime.getHour()
                    + localDateTime.getMinute() / 60.0 + localDateTime.getSecond() / 3600.0;
            double gamma = 2 * Math.PI / 365.0
                    * (dayOfYear - 1 + (fractionalHour - 12) / 24.0);
            double equationOfTime = 229.18 * (0.000075 + 0.001868 * Math.cos(gamma)
                    - 0.032077 * Math.sin(gamma) - 0.014615 * Math.cos(2 * gamma)
                    - 0.040849 * Math.sin(2 * gamma));
            double declination = 0.006918 - 0.399912 * Math.cos(gamma)
                    + 0.070257 * Math.sin(gamma) - 0.006758 * Math.cos(2 * gamma)
                    + 0.000907 * Math.sin(2 * gamma) - 0.002697 * Math.cos(3 * gamma)
                    + 0.00148 * Math.sin(3 * gamma);
            double solarMinutes = fractionalHour * 60 + equationOfTime
                    + 4 * longitude - 60 * zoneOffsetHours;
            double hourAngle = Math.toRadians(solarMinutes / 4 - 180);
            double latitudeRadians = Math.toRadians(latitude);
            double cosineZenith = Math.sin(latitudeRadians) * Math.sin(declination)
                    + Math.cos(latitudeRadians) * Math.cos(declination) * Math.cos(hourAngle);
            return cosineZenith <= Math.cos(Math.toRadians(90.833));
        } catch (java.time.DateTimeException exception) {
            return false;
        }
    }

    private static void refreshDayNightAppearance() {
        if (stationWeatherCode < 0 || stationCity == null
                || stationCity.equals("Rilevamento in corso...")) return;
        ZoneId zone;
        try {
            zone = stationTimezone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(stationTimezone);
        } catch (java.time.DateTimeException exception) {
            zone = ZoneId.systemDefault();
        }
        String localTime = ZonedDateTime.now(zone)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
        boolean night = isNightAt(localTime, zone.getId(), stationLat, stationLon);
        if (night == stationIsNight) return;
        stationIsNight = night;
        loadAppWallpaper(stationWeatherCode, night);
        if (heroIconLabel != null) {
            heroIconLabel.setIcon(new WeatherIcon(stationWeatherCode, 46, night));
        }
        if (rootWindow != null) rootWindow.repaint();
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
                String admin1 = parseJsonString(geoJson, "admin1");
                fetchWeatherAndUpdate(parentFrame, lat, lon, name, country, admin1);

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
                fetchWeatherAndUpdate(parentFrame, locationFix.latitude, locationFix.longitude,
                        city, country, locationFix.admin1);
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
            finishStartupSplash();
            if (mainLocationDisplayLabel != null) {
                mainLocationDisplayLabel.setText("Dati meteo non disponibili");
            }
            if (weatherSourceLabel != null) {
                weatherSourceLabel.setText("Fonte meteo: dati non disponibili");
                weatherSourceStatusText = "Fonte meteo: dati non disponibili";
            }
            JOptionPane.showMessageDialog(parentFrame,
                    message + "\n" + finalDetails, "Errore di Rete", JOptionPane.ERROR_MESSAGE);
        });
    }

    private static void fetchWeatherAndUpdate(JFrame parentFrame, double lat, double lon,
                                              String name, String country, String admin1)
            throws Exception {
        if (!Double.isFinite(lat) || lat < -90 || lat > 90
                || !Double.isFinite(lon) || lon < -180 || lon > 180) {
            throw new IllegalArgumentException("Coordinate geografiche non valide");
        }
        String weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,surface_pressure,wind_speed_10m,is_day" +
                "&hourly=temperature_2m,weather_code,wind_speed_10m,precipitation_probability,is_day" +
                "&daily=uv_index_max,temperature_2m_mean,precipitation_probability_max,wind_speed_10m_mean,weather_code" +
                "&forecast_days=7&timezone=auto";
        String weatherJson;
        String dataSource = "Open-Meteo";
        try {
            weatherJson = executeHttpGET(weatherUrl);
        } catch (Exception primaryError) {
            System.err.println("Open-Meteo non disponibile; provo MET Norway: " + primaryError.getMessage());
            try {
                weatherJson = fetchMetNorwayForecast(lat, lon, ZoneId.systemDefault());
            } catch (Exception fallbackError) {
                throw new java.io.IOException(
                        weatherFallbackErrorMessage(primaryError, fallbackError), fallbackError);
            }
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
        double rawWeatherCode = parseOptionalJsonNumber(currentBlock, "\"weather_code\":");
        double rawIsDay = parseOptionalJsonNumber(currentBlock, "\"is_day\":");
        String hourlyBlock = weatherJson.substring(weatherJson.indexOf("\"hourly\":"));
        String[] hourlyTimes = parseJsonStringArray(hourlyBlock, "\"time\":[");
        double[] hourlyTemps = parseForecastValues(hourlyBlock, "\"temperature_2m\":[", hourlyTimes);
        double[] hourlyCodes = parseForecastValues(hourlyBlock, "\"weather_code\":[", hourlyTimes);
        double[] hourlyWinds = parseForecastValues(hourlyBlock, "\"wind_speed_10m\":[", hourlyTimes);
        double[] hourlyPrecipitation = parseForecastValues(
                hourlyBlock, "\"precipitation_probability\":[", hourlyTimes);
        double[] hourlyIsDay = parseForecastValues(hourlyBlock, "\"is_day\":[", hourlyTimes);
        String[] dailyTimes = parseJsonStringArray(dailyBlock, "\"time\":[");
        double[] dailyMeanTemps = parseForecastValues(dailyBlock, "\"temperature_2m_mean\":[", dailyTimes);
        double[] dailyPrecipitation = parseForecastValues(
                dailyBlock, "\"precipitation_probability_max\":[", dailyTimes);
        double[] dailyMeanWinds = parseForecastValues(dailyBlock, "\"wind_speed_10m_mean\":[", dailyTimes);
        double[] dailyUvIndices = parseForecastValues(dailyBlock, "\"uv_index_max\":[", dailyTimes);
        double[] dailyCodes = parseForecastValues(dailyBlock, "\"weather_code\":[", dailyTimes);
        double uvMax = dailyUvIndices.length == 0 ? Double.NaN : dailyUvIndices[0];

        LinkedHashSet<String> integratedFields = new LinkedHashSet<>();
        boolean supplementUnavailable = false;
        if ("Open-Meteo".equals(dataSource) && hasMissingSupplementableValues(
                t, hum, press, rawWind, rawWeatherCode, hourlyTemps, hourlyCodes, hourlyWinds,
                dailyMeanTemps, dailyMeanWinds, dailyUvIndices, dailyCodes)) {
            try {
                String primaryTimezone = parseJsonString(weatherJson, "timezone");
                ZoneId forecastZone = primaryTimezone.isBlank()
                        ? ZoneId.systemDefault() : ZoneId.of(primaryTimezone);
                String supplementJson = fetchMetNorwayForecast(lat, lon, forecastZone);
                String supplementCurrent = supplementJson.substring(supplementJson.indexOf("\"current\":"));
                String supplementHourly = supplementJson.substring(supplementJson.indexOf("\"hourly\":"));
                String supplementDaily = supplementJson.substring(supplementJson.indexOf("\"daily\":"));
                String[] supplementHourlyTimes = parseJsonStringArray(supplementHourly, "\"time\":[");
                String[] supplementDailyTimes = parseJsonStringArray(supplementDaily, "\"time\":[");

                double fallback = parseOptionalJsonNumber(supplementCurrent, "\"temperature_2m\":");
                if (!Double.isFinite(t) && Double.isFinite(fallback)) {
                    t = fallback;
                    integratedFields.add("temperatura");
                }
                fallback = parseOptionalJsonNumber(supplementCurrent, "\"relative_humidity_2m\":");
                if (!Double.isFinite(hum) && Double.isFinite(fallback)) {
                    hum = fallback;
                    integratedFields.add("umidità");
                }
                fallback = parseOptionalJsonNumber(supplementCurrent, "\"surface_pressure\":");
                if (!Double.isFinite(press) && Double.isFinite(fallback)) {
                    press = fallback;
                    integratedFields.add("pressione");
                }
                fallback = parseOptionalJsonNumber(supplementCurrent, "\"wind_speed_10m\":");
                if (!Double.isFinite(rawWind) && Double.isFinite(fallback)) {
                    rawWind = fallback;
                    integratedFields.add("vento");
                }
                fallback = parseOptionalJsonNumber(supplementCurrent, "\"weather_code\":");
                if (!Double.isFinite(rawWeatherCode) && Double.isFinite(fallback)) {
                    rawWeatherCode = fallback;
                    integratedFields.add("condizioni");
                }

                double[] before = hourlyTemps;
                hourlyTemps = mergeMissingForecastValues(hourlyTimes, hourlyTemps, supplementHourlyTimes,
                        parseNullableJsonDoubleArray(supplementHourly, "\"temperature_2m\":["));
                if (countFiniteValues(hourlyTemps) > countFiniteValues(before)) integratedFields.add("temperatura");
                before = hourlyCodes;
                hourlyCodes = mergeMissingForecastValues(hourlyTimes, hourlyCodes, supplementHourlyTimes,
                        parseNullableJsonDoubleArray(supplementHourly, "\"weather_code\":["));
                if (countFiniteValues(hourlyCodes) > countFiniteValues(before)) integratedFields.add("condizioni");
                before = hourlyWinds;
                hourlyWinds = mergeMissingForecastValues(hourlyTimes, hourlyWinds, supplementHourlyTimes,
                        parseNullableJsonDoubleArray(supplementHourly, "\"wind_speed_10m\":["));
                if (countFiniteValues(hourlyWinds) > countFiniteValues(before)) integratedFields.add("vento");

                before = dailyMeanTemps;
                dailyMeanTemps = mergeMissingForecastValues(dailyTimes, dailyMeanTemps, supplementDailyTimes,
                        parseNullableJsonDoubleArray(supplementDaily, "\"temperature_2m_mean\":["));
                if (countFiniteValues(dailyMeanTemps) > countFiniteValues(before)) integratedFields.add("temperatura");
                before = dailyMeanWinds;
                dailyMeanWinds = mergeMissingForecastValues(dailyTimes, dailyMeanWinds, supplementDailyTimes,
                        parseNullableJsonDoubleArray(supplementDaily, "\"wind_speed_10m_mean\":["));
                if (countFiniteValues(dailyMeanWinds) > countFiniteValues(before)) integratedFields.add("vento");
                before = dailyUvIndices;
                dailyUvIndices = mergeMissingForecastValues(dailyTimes, dailyUvIndices, supplementDailyTimes,
                        parseNullableJsonDoubleArray(supplementDaily, "\"uv_index_max\":["));
                if (countFiniteValues(dailyUvIndices) > countFiniteValues(before)) integratedFields.add("UV");
                before = dailyCodes;
                dailyCodes = mergeMissingForecastValues(dailyTimes, dailyCodes, supplementDailyTimes,
                        parseNullableJsonDoubleArray(supplementDaily, "\"weather_code\":["));
                if (countFiniteValues(dailyCodes) > countFiniteValues(before)) integratedFields.add("condizioni");

            } catch (Exception supplementError) {
                supplementUnavailable = true;
                System.err.println("Integrazione dei dati mancanti da MET Norway non riuscita: "
                        + supplementError.getMessage());
            }
        }
        uvMax = dailyUvIndices.length == 0 ? Double.NaN : dailyUvIndices[0];
        if ("Open-Meteo".equals(dataSource) && !integratedFields.isEmpty()) {
            dataSource = "Open-Meteo + MET Norway";
        }
        int wCode = Double.isFinite(rawWeatherCode) ? (int) rawWeatherCode : -1;
        int forecastStart = 0;
        String currentTime = parseJsonString(currentBlock, "time");
        String forecastTimezone = parseJsonString(weatherJson, "timezone");
        boolean currentIsNight = Double.isFinite(rawIsDay)
                ? rawIsDay < 0.5
                : isNightAt(currentTime, forecastTimezone, lat, lon);
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
                dayCodes[hour] = Double.isFinite(hourlyCodes[i]) ? hourlyCodes[i] : -1;
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
        boolean[] currentForecastNightFlags = new boolean[forecastCount];
        for (int i = 0; i < forecastCount; i++) {
            int hourIndex = forecastStart + i;
            currentForecastNightFlags[i] = Double.isFinite(hourlyIsDay[hourIndex])
                    ? hourlyIsDay[hourIndex] < 0.5
                    : isNightAt(hourlyTimes[hourIndex], forecastTimezone, lat, lon);
            forecastRows[i] = new Object[]{
                    hourlyTimes[hourIndex].substring(11, 16),
                    Double.isFinite(hourlyCodes[hourIndex]) ? (int) hourlyCodes[hourIndex] : -1,
                    Double.isFinite(hourlyTemps[hourIndex])
                            ? Math.round(hourlyTemps[hourIndex]) + "°C" : "N/D",
                    Double.isFinite(hourlyWinds[hourIndex])
                            ? Math.round(hourlyWinds[hourIndex]) + " km/h" : "N/D"
            };
        }
        stationCity = name;
        stationCountry = country;
        stationLat = lat;
        stationLon = lon;
        stationWeatherCode = wCode;
        stationIsNight = currentIsNight;
        stationTimezone = forecastTimezone;
        stationTemp = t;
        stationApparentTemp = appT;
        stationHumidity = hum;
        stationPressure = press;
        stationWindSpeed = rawWind;
        stationUvIndex = uvMax;
        final String selectedDataSource = dataSource;
        final String selectedSourceStatus = weatherSourceStatus(
                selectedDataSource, integratedFields, supplementUnavailable,
                hasMissingValues(hourlyPrecipitation) || hasMissingValues(dailyPrecipitation));
        weatherDataSourceName = selectedDataSource;
        weatherSourceStatusText = selectedSourceStatus;
        final double selectedTemperature = t;
        final double selectedApparentTemperature = appT;
        final double selectedHumidity = hum;
        final double selectedPressure = press;
        final double selectedUvMax = uvMax;

        String desc = resolveDescription(wCode);
        loadAppWallpaper(wCode, currentIsNight);

        SwingUtilities.invokeLater(() -> {
            forecastRowNightFlags = currentForecastNightFlags;
            mainLocationDisplayLabel.setText(country.isEmpty() ? name : name + ", " + country);
            if (webcamPage != null) webcamPage.updateLocation(name, country, lat, lon);
            if (weatherSourceLabel != null) {
                weatherSourceLabel.setText("<html><div style='width:320px;text-align:center;'>"
                        + escapeHtml(selectedSourceStatus) + "</div></html>");
            }
            mainTempDisplayLabel.setText(Double.isFinite(selectedTemperature)
                    ? Math.round(selectedTemperature) + "°" : "N/D");
            if (heroIconLabel != null) heroIconLabel.setIcon(new WeatherIcon(wCode, 46, currentIsNight));
            conditionTextLabel.setText(desc + "  ·  percepita "
                    + (Double.isFinite(selectedApparentTemperature)
                    ? Math.round(selectedApparentTemperature) + "°" : "N/D"));

            humidityMetricBox.updateGaugeData(Double.isFinite(selectedHumidity)
                            ? Math.round(selectedHumidity) + "%" : "N/D",
                    Double.isFinite(selectedHumidity)
                            ? (int) Math.max(0, Math.min(100, Math.round(selectedHumidity))) : 0);
            uvMetricBox.updateGaugeData(Double.isFinite(selectedUvMax)
                            ? String.format(Locale.ITALIAN, "%.1f UV", selectedUvMax) : "N/D",
                    Double.isFinite(selectedUvMax)
                            ? (int) Math.min(100, Math.max(0, Math.round(selectedUvMax / 11.0 * 100))) : 0);
            pressureMetricBox.updateGaugeData(Double.isFinite(selectedPressure)
                            ? Math.round(selectedPressure) + " hPa" : "N/D",
                    Double.isFinite(selectedPressure)
                            ? (int) Math.min(100, Math.max(0, (selectedPressure - 980) * 2)) : 0);
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
            setForecastDates(radarDateSelector, dailyTimes, true);
            if (appNavBar != null) appNavBar.select("DASHBOARD");
            else if (pageCardLayout != null && mainCardContainer != null) {
                pageCardLayout.show(mainCardContainer, "DASHBOARD");
            }

            fetchWeatherAlerts(country, admin1);
            refreshRadarMap(lat, lon);
            fetchAirQuality(lat, lon);

            if (rootWindow != null) {
                applyCurrentTheme(rootWindow);
                rootWindow.repaint();
            }
            finishStartupSplash();
        });
    }

    static String weatherSourceStatus(String dataSource) {
        return weatherSourceStatus(dataSource, Set.of(), false, false);
    }

    static String weatherSourceStatus(String dataSource, Set<String> integratedFields,
                                      boolean supplementUnavailable) {
        return weatherSourceStatus(dataSource, integratedFields, supplementUnavailable, false);
    }

    static String weatherSourceStatus(String dataSource, Set<String> integratedFields,
                                      boolean supplementUnavailable, boolean precipitationUnavailable) {
        if ("MET Norway".equals(dataSource)) {
            return "Fonte meteo: MET Norway · probabilità pioggia non disponibile";
        }
        String status = "Fonte meteo: " + (dataSource == null || dataSource.isBlank()
                ? "non disponibile" : dataSource);
        if (integratedFields != null && !integratedFields.isEmpty()) {
            status += " · dati integrati da MET Norway: " + String.join(", ", integratedFields);
        }
        if (supplementUnavailable) {
            status += integratedFields != null && !integratedFields.isEmpty()
                    ? " · integrazione MET Norway incompleta"
                    : " · integrazione MET Norway non disponibile";
        }
        if ("Open-Meteo + MET Norway".equals(dataSource) && precipitationUnavailable) {
            status += " · probabilità pioggia N/D (MET Norway non la fornisce)";
        }
        return status;
    }

    private static double[] parseForecastValues(String jsonBlock, String key, String[] times) {
        int valuesStart = jsonBlock.indexOf(key);
        if (valuesStart < 0) {
            double[] missingValues = new double[times.length];
            Arrays.fill(missingValues, Double.NaN);
            return missingValues;
        }
        return alignForecastValues(times, parseNullableJsonDoubleArray(jsonBlock, key));
    }

    static double[] alignForecastValues(String[] times, double[] values) {
        if (values.length > times.length) {
            throw new IllegalArgumentException("La risposta contiene più valori che orari di previsione");
        }
        if (values.length == times.length) return values;
        double[] alignedValues = new double[times.length];
        Arrays.fill(alignedValues, Double.NaN);
        System.arraycopy(values, 0, alignedValues, 0, values.length);
        return alignedValues;
    }

    static double[] mergeMissingForecastValues(String[] primaryTimes, double[] primaryValues,
                                               String[] supplementTimes, double[] supplementValues) {
        if (primaryTimes.length != primaryValues.length
                || supplementTimes.length != supplementValues.length) {
            throw new IllegalArgumentException("Orari e valori delle previsioni non corrispondono");
        }
        Map<String, Double> supplementByTime = new LinkedHashMap<>();
        for (int i = 0; i < supplementTimes.length; i++) {
            if (Double.isFinite(supplementValues[i])) {
                supplementByTime.put(supplementTimes[i], supplementValues[i]);
            }
        }
        double[] merged = primaryValues.clone();
        for (int i = 0; i < primaryTimes.length; i++) {
            if (!Double.isFinite(merged[i])) {
                Double replacement = supplementByTime.get(primaryTimes[i]);
                if (replacement != null) merged[i] = replacement;
            }
        }
        return merged;
    }

    private static boolean hasMissingSupplementableValues(double temperature, double humidity,
                                                           double pressure, double wind,
                                                           double weatherCode, double[] hourlyTemps,
                                                           double[] hourlyCodes, double[] hourlyWinds,
                                                           double[] dailyTemps, double[] dailyWinds,
                                                           double[] dailyUv, double[] dailyCodes) {
        return !Double.isFinite(temperature) || !Double.isFinite(humidity)
                || !Double.isFinite(pressure) || !Double.isFinite(wind)
                || !Double.isFinite(weatherCode) || hasMissingValues(hourlyTemps)
                || hasMissingValues(hourlyCodes) || hasMissingValues(hourlyWinds)
                || hasMissingValues(dailyTemps) || hasMissingValues(dailyWinds)
                || hasMissingValues(dailyUv) || hasMissingValues(dailyCodes);
    }

    private static boolean hasMissingValues(double[] values) {
        for (double value : values) {
            if (!Double.isFinite(value)) return true;
        }
        return false;
    }

    private static int countFiniteValues(double[] values) {
        int count = 0;
        for (double value : values) {
            if (Double.isFinite(value)) count++;
        }
        return count;
    }

    private static String conciseErrorMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        message = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return message.length() > 120 ? message.substring(0, 117) + "..." : message;
    }

    static String weatherFallbackErrorMessage(Exception primaryError, Exception fallbackError) {
        return "Open-Meteo non disponibile (" + conciseErrorMessage(primaryError)
                + "); MET Norway non disponibile (" + conciseErrorMessage(fallbackError) + ")";
    }

    private static String fetchMetNorwayForecast(double latitude, double longitude,
                                                 ZoneId forecastZone) throws Exception {
        String lat = String.format(Locale.US, "%.4f", latitude);
        String lon = String.format(Locale.US, "%.4f", longitude);
        String cacheKey = lat + "," + lon + "," + forecastZone.getId();
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
        String forecastJson = normalizeMetNorwayForecast(response, forecastZone);
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

    private static String normalizeMetNorwayForecast(String response, ZoneId forecastZone) throws Exception {
        String[] timeSeries = extractJsonObjectsFromArray(response, "\"timeseries\":[");
        if (timeSeries.length == 0) {
            throw new IllegalStateException("MET Norway non ha restituito previsioni orarie");
        }

        java.util.List<MetNorwayForecastPoint> points = new java.util.ArrayList<>(timeSeries.length);
        for (String timeEntry : timeSeries) {
            String timestamp = parseJsonString(timeEntry, "time");
            if (timestamp.isEmpty()) continue;
            ZonedDateTime localTime = Instant.parse(timestamp).atZone(forecastZone);
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

        ZonedDateTime now = ZonedDateTime.now(forecastZone);
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
                    airQualitySummaryLabel.putClientProperty(
                            PRESERVE_THEME_FOREGROUND_PROPERTY, Boolean.TRUE);
                    airQualityAqiLabel.setText(String.format(Locale.ITALIAN, "Indice europeo AQI: %.0f", aqi));
                    airQualityPm25Label.setText(String.format(Locale.ITALIAN, "PM2.5: %.1f µg/m³", pm25));
                    airQualityPm10Label.setText(String.format(Locale.ITALIAN, "PM10: %.1f µg/m³", pm10));
                    airQualityNo2Label.setText(String.format(Locale.ITALIAN, "NO₂: %.1f µg/m³", no2));
                    setForecastDates(airQualityDateSelector, uniqueDates(times), true);
                    ForecastDateOption selectedDate =
                            (ForecastDateOption) airQualityDateSelector.getSelectedItem();
                    if (selectedDate != null) {
                        airQualityTrendsTitleLabel.setText("Andamento orario · " + selectedDate.displayDate());
                        airQualityTrendsPanel.setSelectedDate(selectedDate.date);
                    }
                    airQualityTrendsPanel.updateData(times, trends);
                    airQualityMetricBox.updateGaugeData(String.format(Locale.ITALIAN, "%.0f AQI", aqi),
                            (int) Math.max(0, Math.min(100, Math.round(aqi))));
                    if (rootWindow != null) applyCurrentTheme(rootWindow);
                });
            } catch (Exception ex) {
                System.err.println("Qualità dell'aria non disponibile: " + ex.getMessage());
                SwingUtilities.invokeLater(() -> {
                    stationEuropeanAqi = Double.NaN;
                    stationPm25 = Double.NaN;
                    stationPm10 = Double.NaN;
                    stationNo2 = Double.NaN;
                    airQualitySummaryLabel.setText("Dati temporaneamente non disponibili");
                    airQualitySummaryLabel.putClientProperty(
                            PRESERVE_THEME_FOREGROUND_PROPERTY, null);
                    airQualitySummaryLabel.setForeground(themeMutedTextColor());
                    airQualityAqiLabel.setText("Indice europeo AQI: --");
                    airQualityPm25Label.setText("PM2.5: -- µg/m³");
                    airQualityPm10Label.setText("PM10: -- µg/m³");
                    airQualityNo2Label.setText("NO₂: -- µg/m³");
                    airQualityTrendsPanel.updateData(new String[0], new double[4][0]);
                    airQualityMetricBox.updateGaugeData("N/D", 0);
                    if (rootWindow != null) applyCurrentTheme(rootWindow);
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
        return executeHttpGET(endpoint, requestToken, userAgent, 6000, 6000);
    }

    private static String executeHttpGET(String endpoint, HttpRequestToken requestToken,
                                         String userAgent, int connectTimeoutMillis,
                                         int readTimeoutMillis) throws Exception {
        HttpsURLConnection conn = openSecureConnection(endpoint);
        URL url = conn.getURL();
        if (requestToken != null) requestToken.register(conn);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(connectTimeoutMillis);
        conn.setReadTimeout(readTimeoutMillis);
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

    private static boolean isRainCode(int code) {
        return (code >= 51 && code <= 67) || (code >= 80 && code <= 82);
    }

    private static boolean isSnowCode(int code) {
        return (code >= 71 && code <= 77) || (code >= 85 && code <= 86);
    }

    private static boolean isThunderCode(int code) {
        return code >= 95;
    }

    static final class WeatherIcon implements Icon {
        private final int weatherCode;
        private final int size;
        private final boolean night;

        WeatherIcon(int weatherCode, int size) {
            this(weatherCode, size, false);
        }

        WeatherIcon(int weatherCode, int size, boolean night) {
            this.weatherCode = weatherCode;
            this.size = size;
            this.night = night;
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.translate(x, y);
            g2.scale(size / 24.0, size / 24.0);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setStroke(new BasicStroke(1.55f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            Color outline = lightTheme ? new Color(54, 75, 98) : new Color(222, 233, 244);
            Color sun = lightTheme ? new Color(193, 111, 0) : new Color(255, 199, 105);
            Color moon = lightTheme ? new Color(82, 105, 139) : new Color(245, 220, 145);
            Color rain = lightTheme ? new Color(27, 111, 174) : new Color(124, 195, 238);
            Color snow = lightTheme ? new Color(45, 122, 172) : new Color(169, 222, 245);

            if (weatherCode == 0) {
                if (night) drawMoon(g2, 12, 12, moon);
                else drawSun(g2, 12, 12, sun);
            } else if (weatherCode == Integer.MIN_VALUE) {
                g2.dispose();
                return;
            } else {
                if (weatherCode >= 0 && weatherCode <= 2) {
                    if (night) drawMoon(g2, 8, 8, moon);
                    else drawSun(g2, 8, 8, sun);
                }
                drawCloud(g2, outline);
                if (weatherCode >= 45 && weatherCode <= 48) {
                    g2.setColor(lightTheme ? new Color(90, 109, 128)
                            : new Color(189, 205, 219));
                    g2.drawLine(5, 17, 19, 17);
                    g2.drawLine(7, 20, 17, 20);
                } else if (isThunderCode(weatherCode)) {
                    g2.setColor(lightTheme ? new Color(184, 91, 0) : sun);
                    Path2D bolt = new Path2D.Double();
                    bolt.moveTo(13, 14);
                    bolt.lineTo(10, 19);
                    bolt.lineTo(13, 19);
                    bolt.lineTo(11, 23);
                    g2.draw(bolt);
                } else if (isSnowCode(weatherCode)) {
                    g2.setColor(snow);
                    drawSnowflake(g2, 8, 20);
                    drawSnowflake(g2, 16, 20);
                } else if (isRainCode(weatherCode) || (weatherCode >= 51 && weatherCode <= 67)) {
                    g2.setColor(rain);
                    g2.drawLine(8, 18, 7, 21);
                    g2.drawLine(13, 18, 12, 21);
                    g2.drawLine(18, 18, 17, 21);
                }
            }
            g2.dispose();
        }

        private static void drawMoon(Graphics2D g2, double cx, double cy, Color color) {
            Path2D crescent = new Path2D.Double();
            crescent.moveTo(cx + 2.5, cy - 6.5);
            crescent.curveTo(cx - 2.5, cy - 5.8, cx - 4.4, cy - 0.8, cx - 2.1, cy + 3.8);
            crescent.curveTo(cx - 0.5, cy + 6.8, cx + 3.1, cy + 7.5, cx + 5.7, cy + 5.6);
            crescent.curveTo(cx + 1.2, cy + 5.7, cx - 0.8, cy + 1.6, cx + 0.1, cy - 2.2);
            crescent.curveTo(cx + 0.5, cy - 4.0, cx + 1.3, cy - 5.5, cx + 2.5, cy - 6.5);
            crescent.closePath();
            g2.setColor(color);
            g2.fill(crescent);
        }

        private static void drawSun(Graphics2D g2, double cx, double cy, Color color) {
            g2.setColor(color);
            g2.draw(new java.awt.geom.Ellipse2D.Double(cx - 3.1, cy - 3.1, 6.2, 6.2));
            for (int i = 0; i < 8; i++) {
                double angle = Math.PI * i / 4;
                double inner = 5.2;
                double outer = 7.2;
                g2.drawLine((int) Math.round(cx + Math.cos(angle) * inner),
                        (int) Math.round(cy + Math.sin(angle) * inner),
                        (int) Math.round(cx + Math.cos(angle) * outer),
                        (int) Math.round(cy + Math.sin(angle) * outer));
            }
        }

        private static void drawCloud(Graphics2D g2, Color color) {
            Path2D cloud = new Path2D.Double();
            cloud.moveTo(5, 16);
            cloud.curveTo(3.2, 16, 2.5, 14.6, 2.5, 13.2);
            cloud.curveTo(2.5, 11.5, 3.8, 10.1, 5.7, 10);
            cloud.curveTo(6.3, 6.7, 9.1, 5.1, 11.8, 6.3);
            cloud.curveTo(13.1, 6.8, 13.9, 7.9, 14.2, 9.1);
            cloud.curveTo(17.2, 8.2, 20.4, 10.2, 20.4, 13.1);
            cloud.curveTo(20.4, 14.8, 19, 16, 17.2, 16);
            cloud.closePath();
            g2.setColor(color);
            g2.draw(cloud);
        }

        private static void drawSnowflake(Graphics2D g2, int cx, int cy) {
            g2.drawLine(cx - 2, cy, cx + 2, cy);
            g2.drawLine(cx, cy - 2, cx, cy + 2);
            g2.drawLine(cx - 1, cy - 1, cx + 1, cy + 1);
            g2.drawLine(cx - 1, cy + 1, cx + 1, cy - 1);
        }
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
        openTelemetryPopup(rootWindow, title, buildMetricInfoPopup(value, description));
    }

    private static Color popupPrimaryTextColor() {
        return new Color(245, 248, 252);
    }

    private static Color popupMutedTextColor() {
        return new Color(183, 197, 212);
    }

    private static JPanel buildCurrentWeatherPopup(String location, String temperature,
                                                   String apparent, String humidity,
                                                   String pressure, String wind, String uv,
                                                   String source, String coordinates) {
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        JLabel locationLabel = new JLabel(location);
        locationLabel.setFont(uiFont(Font.BOLD, 16f));
        locationLabel.setForeground(popupPrimaryTextColor());
        locationLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(locationLabel);
        content.add(Box.createVerticalStrut(12));

        JPanel metrics = new JPanel(new GridLayout(0, 2, 9, 9));
        metrics.setOpaque(false);
        metrics.setAlignmentX(Component.LEFT_ALIGNMENT);
        metrics.setPreferredSize(new Dimension(360, 185));
        metrics.setMaximumSize(new Dimension(380, 185));
        metrics.add(createPopupMetricCard("Temperatura", temperature, "temperature",
                new Color(232, 137, 66)));
        metrics.add(createPopupMetricCard("Percepita", apparent, "feels",
                new Color(236, 164, 73)));
        metrics.add(createPopupMetricCard("Umidità", humidity, "humidity",
                new Color(69, 164, 206)));
        metrics.add(createPopupMetricCard("Pressione", pressure, "pressure",
                new Color(145, 127, 198)));
        metrics.add(createPopupMetricCard("Vento", wind, "wind",
                new Color(70, 166, 131)));
        metrics.add(createPopupMetricCard("Indice UV oggi", uv, "uv",
                new Color(220, 172, 57)));
        content.add(metrics);
        content.add(Box.createVerticalStrut(12));
        content.add(createPopupNote("FONTE", source));
        content.add(Box.createVerticalStrut(7));
        content.add(createPopupNote("COORDINATE", coordinates));
        JLabel hint = new JLabel("Usa la ricerca per consultare un altro comune.");
        hint.setFont(uiFont(Font.PLAIN, 11f));
        hint.setForeground(popupMutedTextColor());
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(Box.createVerticalStrut(8));
        content.add(hint);
        return content;
    }

    private static JPanel buildMetricInfoPopup(String value, String description) {
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(createPopupMetricCard("VALORE PREVISTO", value, "info",
                new Color(76, 144, 197)));
        content.add(Box.createVerticalStrut(12));

        JLabel sectionLabel = new JLabel("COSA SIGNIFICA");
        sectionLabel.setFont(uiFont(Font.BOLD, 10f));
        sectionLabel.setForeground(popupMutedTextColor());
        sectionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(sectionLabel);
        content.add(Box.createVerticalStrut(5));

        JLabel descriptionText = new JLabel("<html><div style='width:320px'>"
                + escapeHtml(description) + "</div></html>");
        descriptionText.setFont(uiFont(Font.PLAIN, 13f));
        descriptionText.setForeground(popupPrimaryTextColor());
        descriptionText.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(descriptionText);
        content.add(Box.createVerticalStrut(12));
        content.add(createPopupNote("DA RICORDARE",
                "È una previsione: il valore può variare in base alle condizioni locali."));
        return content;
    }

    private static JPanel createPopupMetricCard(String label, String value,
                                                String symbol, Color accent) {
        JPanel card = new JPanel() {
            @Override
            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                Graphics2D g2 = (Graphics2D) graphics.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(255, 255, 255, lightTheme ? 24 : 18));
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 13, 13);
                g2.setColor(new Color(255, 255, 255, 42));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 13, 13);
                g2.dispose();
            }
        };
        card.setOpaque(false);
        card.setLayout(new BorderLayout(8, 0));
        card.setBorder(new EmptyBorder(6, 8, 6, 8));
        card.add(new PopupMetricSymbol(symbol, accent), BorderLayout.WEST);
        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel labelText = new JLabel(label);
        labelText.setFont(uiFont(Font.PLAIN, 9f));
        labelText.setForeground(popupMutedTextColor());
        JLabel valueText = new JLabel(value);
        valueText.setFont(uiFont(Font.BOLD, 15f));
        valueText.setForeground(popupPrimaryTextColor());
        text.add(labelText);
        text.add(Box.createVerticalStrut(2));
        text.add(valueText);
        card.add(text, BorderLayout.CENTER);
        card.setMinimumSize(new Dimension(0, 52));
        card.setPreferredSize(new Dimension(150, 56));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));
        return card;
    }

    private static final class PopupMetricSymbol extends JComponent {
        private final String symbol;
        private final Color accent;

        private PopupMetricSymbol(String symbol, Color accent) {
            this.symbol = symbol;
            this.accent = accent;
            setOpaque(false);
            setPreferredSize(new Dimension(26, 26));
            setMinimumSize(new Dimension(26, 26));
            setMaximumSize(new Dimension(26, 26));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(),
                    lightTheme ? 38 : 54));
            g2.fillOval(1, 1, 24, 24);
            g2.setColor(lightTheme ? accent.darker() : accent.brighter());
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (symbol) {
                case "temperature":
                case "feels":
                    g2.drawRoundRect(11, 5, 5, 12, 3, 3);
                    g2.drawLine(13, 8, 13, 17);
                    g2.fillOval(9, 15, 9, 9);
                    break;
                case "humidity":
                    Path2D drop = new Path2D.Double();
                    drop.moveTo(13, 4);
                    drop.curveTo(10, 9, 7, 12, 7, 16);
                    drop.curveTo(7, 20, 9.5, 22, 13, 22);
                    drop.curveTo(16.5, 22, 19, 20, 19, 16);
                    drop.curveTo(19, 12, 16, 9, 13, 4);
                    drop.closePath();
                    g2.draw(drop);
                    break;
                case "pressure":
                    g2.drawArc(6, 7, 14, 14, 0, 180);
                    g2.drawLine(13, 14, 17, 10);
                    g2.fillOval(11, 12, 4, 4);
                    break;
                case "wind":
                    for (int row = 0; row < 3; row++) {
                        int y = 8 + row * 5;
                        Path2D gust = new Path2D.Double();
                        gust.moveTo(5, y);
                        gust.curveTo(9, y - 2, 14, y + 2, 18, y);
                        gust.lineTo(21, y);
                        g2.draw(gust);
                    }
                    break;
                case "uv":
                    g2.drawOval(9, 9, 8, 8);
                    g2.drawLine(13, 4, 13, 7);
                    g2.drawLine(13, 19, 13, 22);
                    g2.drawLine(4, 13, 7, 13);
                    g2.drawLine(19, 13, 22, 13);
                    g2.drawLine(6, 6, 8, 8);
                    g2.drawLine(18, 18, 20, 20);
                    g2.drawLine(18, 8, 20, 6);
                    g2.drawLine(6, 20, 8, 18);
                    break;
                default:
                    g2.drawOval(6, 6, 14, 14);
                    g2.setFont(uiFont(Font.BOLD, 13f));
                    g2.drawString("i", 11, 17);
                    break;
            }
            g2.dispose();
        }
    }

    private static JPanel createPopupNote(String title, String text) {
        JPanel note = new JPanel();
        note.setOpaque(false);
        note.setLayout(new BoxLayout(note, BoxLayout.Y_AXIS));
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(uiFont(Font.BOLD, 9f));
        titleLabel.setForeground(popupMutedTextColor());
        JLabel textLabel = new JLabel("<html><div style='width:310px'>"
                + escapeHtml(text) + "</div></html>");
        textLabel.setFont(uiFont(Font.PLAIN, 11f));
        textLabel.setForeground(popupPrimaryTextColor());
        note.add(titleLabel);
        note.add(Box.createVerticalStrut(3));
        note.add(textLabel);
        return note;
    }

    private static void openTelemetryPopup(JFrame parent, String title, JComponent popupContent) {
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
        eyebrow.setForeground(popupMutedTextColor());
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(uiFont(Font.BOLD, 18f));
        titleLabel.setForeground(popupPrimaryTextColor());
        heading.add(eyebrow);
        heading.add(Box.createVerticalStrut(4));
        heading.add(titleLabel);
        heading.add(Box.createVerticalStrut(10));
        JPanel separator = new JPanel() {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g2 = (Graphics2D) graphics.create();
                g2.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 125),
                        getWidth(), 0, new Color(255, 255, 255, 12)));
                g2.fillRoundRect(0, 0, getWidth(), 1, 1, 1);
                g2.dispose();
            }
        };
        separator.setOpaque(false);
        separator.setPreferredSize(new Dimension(1, 1));
        separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        heading.add(separator);

        popupContent.setBorder(new EmptyBorder(2, 2, 2, 10));
        JScrollPane scroll = new JScrollPane(popupContent);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        styleScrollBar(scroll.getVerticalScrollBar());

        JButton closeBtn = new JButton("Chiudi");
        styleDiscreetButton(closeBtn);
        closeBtn.setFont(uiFont(Font.BOLD, 12f));
        closeBtn.addActionListener(e -> dialog.dispose());
        dialog.getRootPane().setDefaultButton(closeBtn);
        dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        container.add(heading, BorderLayout.NORTH);
        container.add(scroll, BorderLayout.CENTER);
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        footer.setOpaque(false);
        footer.add(closeBtn);
        container.add(footer, BorderLayout.SOUTH);
        dialog.add(container);

        int targetWidth = 440;
        int targetHeight = Math.max(280,
                Math.min(560, popupContent.getPreferredSize().height + 145));
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
            g2.setColor(new Color(0, 0, 0, 150));
            g2.fillRoundRect(3, 5, getWidth() - 6, getHeight() - 8, 34, 34);
            g2.setPaint(new GradientPaint(0, 0, new Color(45, 54, 67, 250),
                    0, getHeight(), new Color(19, 24, 32, 250)));
            g2.fillRoundRect(0, 0, getWidth() - 2, getHeight() - 3, 34, 34);
            Shape previousClip = g2.getClip();
            g2.clip(new java.awt.geom.RoundRectangle2D.Float(1, 1, getWidth() - 4,
                    getHeight() - 5, 34, 34));
            g2.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 43),
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
        private final String[] keys = {"DASHBOARD", "ANALITICA", "RADAR", "ARIA", "WEBCAM"};
        private final String[] labels = {"Meteo", "Analitica", "Radar", "Aria", "Webcam"};
        private final String[] glyphs = {"weather", "chart", "radar", "air", "webcam"};

        AppNavBar() {
            setOpaque(false);
            setPreferredSize(new Dimension(460, 82));
            setLayout(new GridLayout(1, keys.length, 2, 0));
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
                label.setForeground(on ? themePrimaryTextColor()
                        : (lightTheme ? new Color(71, 84, 99) : new Color(185, 192, 202)));
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
            g2.setColor(lightTheme ? new Color(48, 64, 82, 28) : new Color(0, 0, 0, 65));
            g2.fillRoundRect(2, 3, width - 4, height - 5, 40, 40);
            g2.setPaint(lightTheme
                    ? new GradientPaint(0, 1, new Color(239, 246, 253, 176),
                            0, height, new Color(220, 232, 244, 158))
                    : new GradientPaint(0, 1, new Color(112, 121, 137, 105),
                            0, height, new Color(24, 27, 34, 170)));
            g2.fillRoundRect(0, 0, width, height, 40, 40);

            Shape previousClip = g2.getClip();
            g2.clip(new java.awt.geom.RoundRectangle2D.Float(1, 1, width - 2, height - 2, 40, 40));
            g2.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, lightTheme ? 75 : 34),
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
            g2.setColor(active ? themePrimaryTextColor()
                    : (lightTheme ? new Color(100, 115, 132) : new Color(151, 158, 169)));
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
                case "webcam":
                    g2.drawRoundRect(3, 6, 18, 13, 4, 4);
                    g2.drawOval(8, 8, 8, 8);
                    g2.drawLine(8, 6, 10, 3);
                    g2.drawLine(10, 3, 15, 3);
                    if (active) g2.fillOval(10, 10, 4, 4);
                    else g2.drawOval(10, 10, 4, 4);
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
            int count = Math.min(5, rows);
            for (int i = 0; i < count; i++) {
                Object weatherValue = forecastTableModel.getValueAt(i, 1);
                int weatherCode = weatherValue instanceof Number
                        ? ((Number) weatherValue).intValue() : -1;
                add(hourCell(
                        String.valueOf(forecastTableModel.getValueAt(i, 0)),
                        weatherCode,
                        String.valueOf(forecastTableModel.getValueAt(i, 2)),
                        String.valueOf(forecastTableModel.getValueAt(i, 3)),
                        i < forecastRowNightFlags.length && forecastRowNightFlags[i]));
            }
            for (int i = count; i < 5; i++) {
                add(hourCell("--:--", Integer.MIN_VALUE, "--", "--", false));
            }
            revalidate();
            repaint();
        }

        void updateFromForecast(DailyForecast forecast) {
            removeAll();
            for (int i = 0; i < forecast.hourlyTimes.length; i++) {
                String time = forecast.hourlyTimes[i].substring(11, 16);
                int weatherCode = Double.isFinite(forecast.hourlyWeatherCodes[i])
                        ? (int) forecast.hourlyWeatherCodes[i] : -1;
                String temperature = Double.isFinite(forecast.hourlyTemperatures[i])
                        ? String.format(Locale.ITALIAN, "%.0f°", forecast.hourlyTemperatures[i]) : "--°";
                String wind = Double.isFinite(forecast.hourlyWind[i])
                        ? String.format(Locale.ITALIAN, "Vento %.0f km/h", forecast.hourlyWind[i]) : "Vento --";
                boolean night = isNightAt(forecast.hourlyTimes[i],
                        stationTimezone, stationLat, stationLon);
                add(hourCell(time, weatherCode, temperature, wind, night));
            }
            int visibleSlots = Math.max(5, forecast.hourlyTimes.length);
            for (int i = forecast.hourlyTimes.length; i < visibleSlots; i++) {
                add(hourCell("--:--", Integer.MIN_VALUE, "--", "--", false));
            }
            setPreferredSize(new Dimension(stripWidth(visibleSlots), 118));
            revalidate();
            repaint();
        }

        private static final int CELL_WIDTH = 84;
        private static final int CELL_GAP = 6;

        private static int minimumStripWidth() {
            return stripWidth(5);
        }

        private static int stripWidth(int cells) {
            return cells * CELL_WIDTH + (cells + 1) * CELL_GAP;
        }

        private JPanel hourCell(String time, int weatherCode, String temp, String wind, boolean night) {
            JPanel cell = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(lightTheme
                            ? new Color(239, 246, 253, 166) : new Color(10, 13, 18, 190));
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18);
                    g2.setColor(lightTheme
                            ? new Color(86, 104, 126, 115) : new Color(255, 255, 255, 76));
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
                    g2.dispose();
                    super.paintComponent(g);
                }
            };
            cell.setOpaque(false);
            cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
            cell.setBorder(new EmptyBorder(10, 4, 10, 4));
            cell.setPreferredSize(new Dimension(CELL_WIDTH, 118));
            cell.setMinimumSize(new Dimension(CELL_WIDTH, 118));
            cell.setMaximumSize(new Dimension(CELL_WIDTH, 118));

            JLabel timeLabel = centeredLabel(time, Font.BOLD, 12f, themeMutedTextColor());
            JLabel iconLabel = new JLabel(new WeatherIcon(weatherCode, 25, night));
            iconLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            JLabel tempLabel = centeredLabel(temp, Font.BOLD, 14f, themePrimaryTextColor());
            JLabel windLabel = centeredLabel(wind, Font.BOLD, 10f, themeMutedTextColor());
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
            g2.setColor(themePrimaryTextColor());
            g2.drawString(title, Math.max(8, (getWidth() - titleWidth) / 2), 22);

            int diameter = Math.max(1, Math.min(62, getHeight() - 38));
            int x = (getWidth() - diameter) / 2;
            int y = 26 + Math.max(0, (getHeight() - 32 - diameter) / 2);
            g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 36));
            g2.fillOval(x - 8, y - 8, diameter + 16, diameter + 16);

            g2.setStroke(new BasicStroke(5.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(lightTheme ? new Color(87, 105, 124, 160)
                    : new Color(205, 215, 226, 70));
            g2.draw(new Arc2D.Float(x, y, diameter, diameter, 90, -270, Arc2D.OPEN));
            g2.setColor(accent);
            g2.draw(new Arc2D.Float(x, y, diameter, diameter, 90, -(int) (270 * percent / 100.0), Arc2D.OPEN));

            g2.setColor(themePrimaryTextColor());
            int valueFontSize = value.length() > 7 ? 10 : 13;
            g2.setFont(uiFont(Font.BOLD, valueFontSize));
            fm = g2.getFontMetrics();
            int valueWidth = fm.stringWidth(value);
            g2.drawString(value, (getWidth() - valueWidth) / 2, y + (diameter + fm.getAscent() - fm.getDescent()) / 2);

            g2.dispose();
        }
    }
}
