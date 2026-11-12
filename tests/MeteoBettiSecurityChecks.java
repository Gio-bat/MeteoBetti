import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.prefs.Preferences;

public final class MeteoBettiSecurityChecks {

    public static void main(String[] args) throws Exception {
        verifyAllowedHostsAndHttps();
        verifyOfficialWeatherAlertParsing();
        verifyLocationNameHandling();
        verifyThemePreferenceHandling();
        verifyStartupSplashMinimumDuration();
        verifyLocationSuggestionEnterBehavior();
        verifyHourlyTimelineKeepsFiveFixedCells();
        verifyWeatherWallpaperSelection();
        verifyWindyWebcamLink();
        verifyTileImageLimits();
        verifyWeatherIconsRender();
        verifyLocationSuggestionParsing();
        verifyFixedRadarRadius();
        verifyForecastDateReset();
        verifyForecastAvailabilityMessages();
        verifyWeatherFallbackErrorMessage();
        verifySupplementalForecastMerging();
        System.out.println("Verifiche MeteoBetti superate.");
    }

    private static void verifyAllowedHostsAndHttps() throws Exception {
        check(MeteoBettiUltimateOS.isAllowedRemoteHost("api.open-meteo.com"),
                "Open-Meteo deve essere autorizzato");
        check(MeteoBettiUltimateOS.isAllowedRemoteHost("tilecache.rainviewer.com"),
                "I tile RainViewer devono essere autorizzati");
        check(MeteoBettiUltimateOS.isAllowedRemoteHost("feeds.meteoalarm.org"),
                "Il feed ufficiale delle allerte deve essere autorizzato");
        check(!MeteoBettiUltimateOS.isAllowedRemoteHost("rainviewer.com.attacker.example"),
                "Un dominio che imita RainViewer deve essere rifiutato");

        var connection = MeteoBettiUltimateOS.openSecureConnection(
                "https://api.open-meteo.com/v1/forecast");
        try {
            check(!connection.getInstanceFollowRedirects(), "I redirect HTTP devono essere disabilitati");
        } finally {
            connection.disconnect();
        }

        expectRejected("http://api.open-meteo.com/v1/forecast");
        expectRejected("https://attacker.example/");
        expectRejected("https://user@api.open-meteo.com/");
        expectRejected("https://api.open-meteo.com:8443/");
    }

    private static void verifyOfficialWeatherAlertParsing() throws Exception {
        String feed = """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom"
                      xmlns:cap="urn:oasis:names:tc:emergency:cap:1.2">
                  <entry>
                    <cap:areaDesc>Piemonte</cap:areaDesc>
                    <cap:event>Yellow Rain Warning</cap:event>
                    <cap:severity>Moderate</cap:severity>
                    <cap:status>Actual</cap:status>
                    <cap:message_type>Alert</cap:message_type>
                    <cap:effective>2026-01-01T08:00:00+00:00</cap:effective>
                    <cap:expires>2026-01-01T12:00:00+00:00</cap:expires>
                    <cap:identifier>yellow-rain</cap:identifier>
                  </entry>
                  <entry>
                    <cap:areaDesc>Piemonte</cap:areaDesc>
                    <cap:event>Red Flood Warning</cap:event>
                    <cap:severity>Extreme</cap:severity>
                    <cap:status>Actual</cap:status>
                    <cap:message_type>Update</cap:message_type>
                    <cap:effective>2026-01-01T08:00:00+00:00</cap:effective>
                    <cap:expires>2026-01-01T11:00:00+00:00</cap:expires>
                    <cap:identifier>red-flood</cap:identifier>
                  </entry>
                  <entry>
                    <cap:areaDesc>Lazio</cap:areaDesc>
                    <cap:event>Red Thunderstorm Warning</cap:event>
                    <cap:severity>Extreme</cap:severity>
                    <cap:status>Actual</cap:status>
                    <cap:message_type>Alert</cap:message_type>
                    <cap:effective>2026-01-01T08:00:00+00:00</cap:effective>
                    <cap:expires>2026-01-01T12:00:00+00:00</cap:expires>
                    <cap:identifier>other-region</cap:identifier>
                  </entry>
                  <entry>
                    <cap:areaDesc>Piemonte</cap:areaDesc>
                    <cap:event>Red Wind Warning</cap:event>
                    <cap:severity>Extreme</cap:severity>
                    <cap:status>Actual</cap:status>
                    <cap:message_type>Cancel</cap:message_type>
                    <cap:effective>2026-01-01T08:00:00+00:00</cap:effective>
                    <cap:expires>2026-01-01T12:00:00+00:00</cap:expires>
                    <cap:identifier>cancelled</cap:identifier>
                  </entry>
                  <entry>
                    <cap:areaDesc>Piemonte</cap:areaDesc>
                    <cap:event>Red Wind Warning</cap:event>
                    <cap:severity>Extreme</cap:severity>
                    <cap:status>Actual</cap:status>
                    <cap:message_type>Alert</cap:message_type>
                    <cap:effective>2025-12-01T08:00:00+00:00</cap:effective>
                    <cap:expires>2025-12-01T12:00:00+00:00</cap:expires>
                    <cap:identifier>expired</cap:identifier>
                  </entry>
                </feed>
                """;
        var alerts = MeteoBettiUltimateOS.parseWeatherAlerts(feed, "Piemonte",
                java.time.Instant.parse("2026-01-01T10:00:00Z"));
        check(alerts.size() == 2, "Mostra solo avvisi attivi, pubblicati e della regione scelta");
        check(alerts.get(0).severityLevel == 3
                        && alerts.get(0).severityLabel.equals("ALLERTA ROSSA"),
                "Gli avvisi più gravi devono essere ordinati prima e identificati in rosso");
        check(alerts.stream().anyMatch(alert -> alert.severityLevel == 1
                        && alert.event.equals("Yellow Rain Warning")),
                "Le allerte gialle devono mantenere la gravità segnalata dalla fonte");
        check(MeteoBettiUltimateOS.parseWeatherAlerts(feed, "Lazio",
                        java.time.Instant.parse("2026-01-01T10:00:00Z")).size() == 1,
                "Gli avvisi devono essere filtrati sulla regione cercata");

        String externalEntity = "<!DOCTYPE feed [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<feed xmlns=\"http://www.w3.org/2005/Atom\"><entry>&xxe;</entry></feed>";
        try {
            MeteoBettiUltimateOS.parseWeatherAlerts(externalEntity, "Piemonte",
                    java.time.Instant.parse("2026-01-01T10:00:00Z"));
            throw new AssertionError("Il parser allerte deve rifiutare entità XML esterne");
        } catch (org.xml.sax.SAXException expected) {
            // External XML entities must be rejected.
        }
    }

    private static void verifyLocationNameHandling() throws Exception {
        String normalized = MeteoBettiUltimateOS.normalizeDisplayName("  Giò\nBetti\t ");
        check(normalized.equals("GiòBetti"), "Il nome salvato deve rimuovere i caratteri di controllo");
        check(MeteoBettiUltimateOS.normalizeDisplayName(" \n").equals("Utente"),
                "Un nome vuoto deve usare il nome predefinito");
        check(MeteoBettiUltimateOS.normalizeDisplayName("A".repeat(50)).codePointCount(0,
                        MeteoBettiUltimateOS.normalizeDisplayName("A".repeat(50)).length()) == 40,
                "Il nome deve essere limitato a 40 caratteri");

        Preferences preferences = Preferences.userRoot()
                .node("MeteoBetti/Tests/" + UUID.randomUUID());
        try {
            MeteoBettiUltimateOS.saveDisplayName(preferences, "  MeteoBetti  ");
            check(MeteoBettiUltimateOS.loadDisplayName(preferences).equals("MeteoBetti"),
                    "Il nome utente deve persistere nelle preferenze");
        } finally {
            preferences.removeNode();
            Preferences.userRoot().flush();
        }
    }

    private static void verifyThemePreferenceHandling() throws Exception {
        Preferences preferences = Preferences.userRoot()
                .node("MeteoBetti/Tests/" + UUID.randomUUID());
        try {
            MeteoBettiUltimateOS.saveLightTheme(preferences, true);
            check(MeteoBettiUltimateOS.loadLightTheme(preferences),
                    "La preferenza del tema chiaro deve persistere");
            MeteoBettiUltimateOS.saveLightTheme(preferences, false);
            check(!MeteoBettiUltimateOS.loadLightTheme(preferences),
                    "La preferenza deve consentire il ripristino del tema scuro");
        } finally {
            preferences.removeNode();
            Preferences.userRoot().flush();
        }
    }

    private static void verifyHourlyTimelineKeepsFiveFixedCells() {
        var previousModel = MeteoBettiUltimateOS.forecastTableModel;
        MeteoBettiUltimateOS.forecastTableModel = new javax.swing.table.DefaultTableModel(
                new Object[][]{{"14:00", 0, "20°C", "5 km/h"},
                        {"15:00", 1, "21°C", "7 km/h"}},
                new Object[]{"Orario", "Meteo", "Temp.", "Vento"});
        try {
            MeteoBettiUltimateOS.HourlyTimelineStrip strip =
                    new MeteoBettiUltimateOS.HourlyTimelineStrip();
            check(strip.getComponentCount() == 5,
                    "La timeline deve mantenere cinque celle anche quando restano poche ore");
            int firstWidth = strip.getComponent(0).getPreferredSize().width;
            for (int index = 1; index < strip.getComponentCount(); index++) {
                check(strip.getComponent(index).getPreferredSize().width == firstWidth,
                        "Le celle orarie devono mantenere la stessa larghezza");
            }
        } finally {
            MeteoBettiUltimateOS.forecastTableModel = previousModel;
        }
    }

    private static void verifyWeatherWallpaperSelection() {
        check(MeteoBettiUltimateOS.wallpaperFileName(0).equals("sfondo_sole.jpg"),
                "Il cielo sereno deve usare lo sfondo del sole");
        check(MeteoBettiUltimateOS.wallpaperFileName(1).equals("sfondo_nuvoloso.jpg")
                        && MeteoBettiUltimateOS.wallpaperFileName(2).equals("sfondo_nuvoloso.jpg")
                        && MeteoBettiUltimateOS.wallpaperFileName(3).equals("sfondo_nuvoloso.jpg"),
                "Le condizioni da poco nuvoloso a coperto devono usare lo sfondo nuvoloso");
        check(MeteoBettiUltimateOS.wallpaperFileName(45).equals("sfondo_nuvoloso.jpg")
                        && MeteoBettiUltimateOS.wallpaperFileName(48).equals("sfondo_nuvoloso.jpg"),
                "La nebbia deve usare lo sfondo nuvoloso");
        check(MeteoBettiUltimateOS.wallpaperFileName(61).equals("sfondo_pioggia.jpg")
                        && MeteoBettiUltimateOS.wallpaperFileName(95).equals("sfondo_pioggia.jpg"),
                "Pioggia e temporale devono usare lo sfondo della pioggia");
        check(MeteoBettiUltimateOS.wallpaperFileName(0, true).equals("sfondo_notte_sole.jpg")
                        && MeteoBettiUltimateOS.wallpaperFileName(3, true).equals("sfondo_notte_nuvoloso.jpg")
                        && MeteoBettiUltimateOS.wallpaperFileName(61, true).equals("sfondo_notte_pioggia.jpg"),
                "La notte deve selezionare gli sfondi notturni con i tag corretti");
        check(MeteoBettiUltimateOS.wallpaperFileName(0, false).equals("sfondo_sole.jpg"),
                "Di giorno gli sfondi esistenti devono restare invariati");
        check(!MeteoBettiUltimateOS.isNightAt("2026-06-21T12:00", "Europe/Rome",
                        41.9028, 12.4964)
                        && MeteoBettiUltimateOS.isNightAt("2026-06-21T00:00", "Europe/Rome",
                        41.9028, 12.4964),
                "Il rilevamento solare deve distinguere giorno e notte nell'ora locale");
    }

    private static void verifyStartupSplashMinimumDuration() {
        check(MeteoBettiUltimateOS.startupSplashRemainingMillis(0) == 2_000,
                "La schermata di avvio deve restare visibile almeno due secondi");
        check(MeteoBettiUltimateOS.startupSplashRemainingMillis(1_999) == 1
                        && MeteoBettiUltimateOS.startupSplashRemainingMillis(2_000) == 0
                        && MeteoBettiUltimateOS.startupSplashRemainingMillis(3_000) == 0,
                "La schermata di avvio deve chiudersi appena raggiunta la durata minima");
    }

    private static void verifyWindyWebcamLink() {
        var uri = MeteoBettiUltimateOS.buildWindyWebcamsUri(45.0703, 7.6869);
        check(uri.getScheme().equals("https") && uri.getHost().equals("www.windy.com"),
                "La sezione webcam deve aprire solo il sito HTTPS ufficiale di Windy");
        check(uri.getPath().equals("/it/-Webcam/webcams")
                        && uri.getQuery().equals("45.0703,7.6869,8"),
                "La mappa webcam deve centrarsi sulle coordinate della località cercata");
        try {
            MeteoBettiUltimateOS.buildWindyWebcamsUri(Double.NaN, 0);
            throw new AssertionError("Coordinate non valide non devono generare un link webcam");
        } catch (IllegalArgumentException expected) {
            // Invalid coordinates must not produce an external URL.
        }
    }

    private static void verifyTileImageLimits() throws Exception {
        BufferedImage validImage = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream validBytes = new ByteArrayOutputStream();
        ImageIO.write(validImage, "png", validBytes);
        BufferedImage decoded = MeteoBettiUltimateOS.decodeTileImage(validBytes.toByteArray());
        check(decoded.getWidth() == 256 && decoded.getHeight() == 256,
                "Le tile cartografiche standard devono essere accettate");

        BufferedImage oversizedImage = new BufferedImage(1025, 1, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream oversizedBytes = new ByteArrayOutputStream();
        ImageIO.write(oversizedImage, "png", oversizedBytes);
        try {
            MeteoBettiUltimateOS.decodeTileImage(oversizedBytes.toByteArray());
            throw new AssertionError("Un'immagine tile sovradimensionata deve essere rifiutata");
        } catch (IllegalArgumentException expected) {
            // Expected: remote tile dimensions are bounded before decoding.
        }
    }

    private static void verifyWeatherIconsRender() {
        int[] weatherCodes = {0, 2, 3, 45, 61, 71, 95, -1};
        for (int code : weatherCodes) {
            for (boolean night : new boolean[]{false, true}) {
                MeteoBettiUltimateOS.WeatherIcon icon =
                        new MeteoBettiUltimateOS.WeatherIcon(code, 32, night);
                BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D graphics = image.createGraphics();
                icon.paintIcon(null, graphics, 0, 0);
                graphics.dispose();
                boolean hasVisiblePixels = false;
                for (int y = 0; y < image.getHeight() && !hasVisiblePixels; y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        if ((image.getRGB(x, y) >>> 24) != 0) {
                            hasVisiblePixels = true;
                            break;
                        }
                    }
                }
                check(hasVisiblePixels, "L'icona meteo deve essere disegnata per il codice "
                        + code + (night ? " di notte" : " di giorno"));
            }
        }
        BufferedImage dayMoonWeather = renderWeatherIcon(0, false);
        BufferedImage nightMoonWeather = renderWeatherIcon(0, true);
        boolean dayNightIconDiffers = false;
        for (int y = 0; y < dayMoonWeather.getHeight() && !dayNightIconDiffers; y++) {
            for (int x = 0; x < dayMoonWeather.getWidth(); x++) {
                if (dayMoonWeather.getRGB(x, y) != nightMoonWeather.getRGB(x, y)) {
                    dayNightIconDiffers = true;
                    break;
                }
            }
        }
        check(dayNightIconDiffers, "Con cielo sereno l'icona notturna deve mostrare la luna");
    }

    private static BufferedImage renderWeatherIcon(int code, boolean night) {
        MeteoBettiUltimateOS.WeatherIcon icon =
                new MeteoBettiUltimateOS.WeatherIcon(code, 32, night);
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        icon.paintIcon(null, graphics, 0, 0);
        graphics.dispose();
        return image;
    }

    private static void verifyLocationSuggestionParsing() {
        String response = "{\"results\":["
                + "{\"name\":\"Torino\",\"latitude\":45.0703,\"longitude\":7.6869,"
                + "\"country\":\"Italia\",\"admin1\":\"Piemonte\"},"
                + "{\"name\":\"San \\\"Luca\\\"\",\"latitude\":44.5,\"longitude\":11.3,"
                + "\"country\":\"Italia\",\"admin1\":null,\"admin1_id\":42},"
                + "{\"name\":\"Coordinate errate\",\"latitude\":100,\"longitude\":0,"
                + "\"country\":\"Italia\",\"admin1\":\"Test\"}]}";
        var suggestions = MeteoBettiUltimateOS.parseLocationSuggestions(response);
        check(suggestions.size() == 2, "I suggerimenti devono ignorare coordinate non valide");
        check(suggestions.get(0).displayName().equals("Torino, Piemonte")
                        && suggestions.get(0).subtitle().equals("Piemonte · Italia"),
                "Il suggerimento deve mostrare città, provincia/regione e Paese");
        check(suggestions.get(1).name.equals("San \"Luca\"")
                        && suggestions.get(1).admin1.isEmpty()
                        && suggestions.get(1).displayName().equals("San \"Luca\""),
                "Il parser deve gestire stringhe escape e province non disponibili");
        check(Math.abs(suggestions.get(0).latitude - 45.0703) < 0.0001
                        && Math.abs(suggestions.get(0).longitude - 7.6869) < 0.0001,
                "Il suggerimento deve conservare le coordinate della località selezionata");
        check(MeteoBettiUltimateOS.parseLocationSuggestions("{\"results\":[] }").isEmpty(),
                "Una risposta senza risultati deve produrre una lista vuota");
    }

    private static void verifyLocationSuggestionEnterBehavior() {
        check(MeteoBettiUltimateOS.shouldSelectLocationSuggestionOnEnter(true, 1, 3),
                "Invio deve selezionare un suggerimento evidenziato");
        check(!MeteoBettiUltimateOS.shouldSelectLocationSuggestionOnEnter(true, -1, 3)
                        && !MeteoBettiUltimateOS.shouldSelectLocationSuggestionOnEnter(false, 0, 3)
                        && !MeteoBettiUltimateOS.shouldSelectLocationSuggestionOnEnter(true, 3, 3),
                "Invio senza una selezione valida deve cercare il testo digitato");
    }

    private static void verifyFixedRadarRadius() {
        for (double latitude : new double[]{-80, -45, 0, 45, 80}) {
            int zoom = MeteoBettiUltimateOS.calculateRadarZoom(latitude, 512, 100);
            check(zoom <= MeteoBettiUltimateOS.MAX_RADAR_TILE_ZOOM,
                    "Il radar non deve chiedere tile oltre il livello supportato");
            double scale = MeteoBettiUltimateOS.radarTileScale(latitude, zoom, 512, 100);
            double clampedLatitude = Math.max(-85.05112878, Math.min(85.05112878, latitude));
            double metersPerTilePixel = 40_075_016.686
                    * Math.cos(Math.toRadians(clampedLatitude)) / (256.0 * (1 << zoom));
            double actualRadiusKm = 512.0 * metersPerTilePixel / scale / 2000.0;
            check(Math.abs(actualRadiusKm - 100) < 0.01,
                    "La mappa radar deve mantenere il raggio richiesto a ogni latitudine");
        }

        try {
            MeteoBettiUltimateOS.calculateRadarZoom(Double.NaN, 512, 100);
            throw new AssertionError("Coordinate radar non valide devono essere rifiutate");
        } catch (IllegalArgumentException expected) {
            // Expected: invalid map coordinates must never reach the tile calculation.
        }
    }

    private static void verifyForecastDateReset() {
        var selector = new javax.swing.JComboBox<MeteoBettiUltimateOS.ForecastDateOption>();
        String today = java.time.LocalDate.now().toString();
        String[] firstLocationDates = {
                today,
                java.time.LocalDate.now().plusDays(1).toString(),
                java.time.LocalDate.now().plusDays(2).toString()
        };
        MeteoBettiUltimateOS.setForecastDates(selector, firstLocationDates);
        selector.setSelectedIndex(2);
        check(selector.getSelectedIndex() == 2,
                "Il test deve simulare un giorno successivo selezionato nella località precedente");

        String[] newLocationDates = {
                today,
                java.time.LocalDate.now().plusDays(1).toString()
        };
        MeteoBettiUltimateOS.setForecastDates(selector, newLocationDates, true);
        check(selector.getSelectedIndex() == 0,
                "Una nuova località deve mostrare il primo giorno disponibile, non la selezione precedente");
    }

    private static void verifyForecastAvailabilityMessages() {
        check(MeteoBettiUltimateOS.analyticsNoDataMessage(
                        "Probabilità pioggia", 7, 0, "MET Norway")
                        .equals("Probabilità pioggia non disponibile da MET Norway"),
                "Il fallback deve spiegare che non fornisce probabilità di pioggia");
        check(MeteoBettiUltimateOS.analyticsNoDataMessage(
                        "Vento medio", 7, 0, "Open-Meteo")
                        .equals("Dato non disponibile da Open-Meteo"),
                "I dati mancanti non devono sembrare ancora in caricamento");
        check(MeteoBettiUltimateOS.analyticsNoDataMessage(
                        "Vento medio", 0, 0, "Open-Meteo")
                        .equals("Previsioni giornaliere non caricate"),
                "La schermata deve distinguere le previsioni non ancora caricate");
        check(MeteoBettiUltimateOS.analyticsNoDataMessage(
                        "Vento medio", 7, 1, "Open-Meteo").isEmpty(),
                "Un grafico con almeno un valore non deve mostrare un messaggio di assenza");
        check(MeteoBettiUltimateOS.weatherSourceStatus("MET Norway")
                        .contains("MET Norway") && MeteoBettiUltimateOS.weatherSourceStatus("MET Norway")
                        .contains("probabilità pioggia non disponibile"),
                "La fonte alternativa e il suo dato pioggia mancante devono essere visibili");
        check(MeteoBettiUltimateOS.weatherSourceStatus("Open-Meteo").equals("Fonte meteo: Open-Meteo"),
                "La fonte principale deve essere mostrata");
    }

    private static void verifyWeatherFallbackErrorMessage() {
        String message = MeteoBettiUltimateOS.weatherFallbackErrorMessage(
                new IOException("Risposta HTTP 429\nlimite superato"),
                new IOException("timeout\nrete non raggiungibile"));
        check(message.contains("HTTP 429 limite superato")
                        && message.contains("timeout rete non raggiungibile"),
                "Se entrambe le fonti falliscono, l'errore deve spiegare il problema di ciascuna");
    }

    private static void verifySupplementalForecastMerging() {
        String[] primaryTimes = {"2026-10-24T12:00", "2026-10-24T13:00", "2026-10-24T14:00"};
        String[] supplementTimes = {"2026-10-24T12:00", "2026-10-24T13:00", "2026-10-24T15:00"};
        double[] primaryValues = {12.0, Double.NaN, Double.NaN};
        double[] supplementValues = {99.0, 13.0, 15.0};
        double[] merged = MeteoBettiUltimateOS.mergeMissingForecastValues(
                primaryTimes, primaryValues, supplementTimes, supplementValues);
        check(merged[0] == 12.0,
                "L'integrazione non deve sovrascrivere un valore già fornito dalla fonte principale");
        check(merged[1] == 13.0,
                "L'integrazione deve compilare i valori mancanti quando l'orario coincide");
        check(Double.isNaN(merged[2]),
                "L'integrazione non deve associare dati di orari differenti");
        check(Double.isNaN(primaryValues[1]),
                "L'integrazione non deve modificare l'array della fonte principale");

        double[] aligned = MeteoBettiUltimateOS.alignForecastValues(
                primaryTimes, new double[]{12.0, 13.0});
        check(aligned.length == primaryTimes.length && Double.isNaN(aligned[2]),
                "I valori omessi dalla fonte devono essere rappresentati come non disponibili");

        String status = MeteoBettiUltimateOS.weatherSourceStatus(
                "Open-Meteo + MET Norway", java.util.Set.of("UV", "vento"), false, true);
        check(status.contains("Open-Meteo") && status.contains("MET Norway")
                        && status.contains("UV") && status.contains("vento")
                        && status.contains("probabilità pioggia N/D"),
                "L'interfaccia deve indicare le fonti, i campi integrati e la pioggia non disponibile");
        check(MeteoBettiUltimateOS.weatherSourceStatus(
                        "Open-Meteo", java.util.Set.of(), true)
                        .contains("integrazione MET Norway non disponibile"),
                "L'interfaccia deve segnalare se la fonte integrativa non risponde");
    }

    private static void expectRejected(String endpoint) throws Exception {
        try {
            MeteoBettiUltimateOS.openSecureConnection(endpoint);
            throw new AssertionError("L'endpoint doveva essere rifiutato: " + endpoint);
        } catch (IOException expected) {
            // Expected: only approved HTTPS endpoints are accepted.
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
