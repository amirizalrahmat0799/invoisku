package io.github.invoisku;

import io.github.invoisku.codes.PaymentMode;
import io.github.invoisku.codes.State;
import io.github.invoisku.document.Address;
import io.github.invoisku.document.Invoice;
import io.github.invoisku.document.LineItem;
import io.github.invoisku.document.LineTax;
import io.github.invoisku.document.Party;
import io.github.invoisku.sign.SigningCredentials;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Shared fixtures. */
public final class TestData {
    private TestData() {}

    public static final Instant NOW = Instant.parse("2026-10-01T02:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    public static final OffsetDateTime ISSUED = OffsetDateTime.parse("2026-10-01T09:30:00+08:00");

    public static Party supplier() {
        return Party.builder()
                .tin("C25845632020")
                .brn("202001234567")
                .sstNumber("W10-1808-32000012")
                .name("Kedai Kopi Sentosa Sdn Bhd")
                .address(Address.malaysia(List.of("Lot 66, Jalan Merdeka", "Taman Sentosa"), "50480",
                        "Kuala Lumpur", State.KUALA_LUMPUR))
                .phone("+60123456789")
                .email("billing@sentosa.example")
                .msic("56103", "Restaurants and restaurant cum night clubs")
                .build();
    }

    public static Party buyer() {
        return Party.builder()
                .tin("IG21136626090")
                .nric("880101145566")
                .name("Siti Aminah binti Abdullah")
                .address(Address.malaysia("No. 5, Jalan Bunga Raya", "43000", "Kajang", State.SELANGOR))
                .phone("+60198765432")
                .email("siti@example.com")
                .build();
    }

    /** 2 x 1436.50 laptops (10% sales tax, 100.00 discount) + 1 x 120.00 installation (8% service tax). */
    public static Invoice.Builder invoice() {
        return Invoice.invoice()
                .number("INV-2026-0001")
                .issuedAt(ISSUED)
                .supplier(supplier())
                .buyer(buyer())
                .payment(PaymentMode.BANK_TRANSFER, "5141-2345-6789")
                .line(LineItem.builder()
                        .classification("003")
                        .description("Laptop 14\" Pro")
                        .quantity("2", "C62")
                        .unitPrice("1436.50")
                        .discount("Promotion", "100.00")
                        .tax(LineTax.sales("10"))
                        .build())
                .line(LineItem.builder()
                        .classification("022")
                        .description("Installation service")
                        .quantity("1")
                        .unitPrice("120.00")
                        .tax(LineTax.service("8"))
                        .build());
    }

    public static String resource(String path) {
        try (InputStream in = TestData.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static SigningCredentials credentials() {
        try (InputStream in = TestData.class.getResourceAsStream("/certs/test-signing.p12")) {
            return SigningCredentials.fromPkcs12(in, "changeit".toCharArray(), null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
