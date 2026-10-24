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
        System.out.println("Verifiche di sicurezza MeteoBetti superate.");
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
