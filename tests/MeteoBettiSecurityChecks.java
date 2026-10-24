import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.prefs.Preferences;

public final class MeteoBettiSecurityChecks {

    public static void main(String[] args) throws Exception {
        verifyAllowedHostsAndHttps();
        verifyLocationNameHandling();
        verifyTileImageLimits();
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
