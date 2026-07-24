package za.co.fnb.dcre.mrg.domain;

/**
 * The mandate state-delta report flat-file layout, a SYNTHETIC-CONTRACT (A-57
 * class): the real legacy OnHost response copybook and its exact bytes are
 * unrecovered, so this is a stand-in shape carrying the A-item marker by
 * design. Header {@code MSD|client|window}, one {@code MND|mandate_ref|state}
 * per reported mandate, trailer {@code END|count}. A quiet window emits the
 * zero-delta HEARTBEAT: header + a single {@code HB|<placeholder>} line +
 * {@code END|0}, so the OnHost consumer tells "no movement" from "MRG dead".
 * A real delta always carries at least one MND line (count >= 1), so
 * {@code END|0} uniquely classifies a heartbeat file even without its registry row.
 */
public final class ManReportLayout {

    public static final String FILE_INFIX = "_MSD_";
    public static final String FILE_SUFFIX = ".txt";
    public static final String DETAIL_PREFIX = "MND|";

    /** Heartbeat zero placeholder, DCRE + 29 zeros = 33 chars (Max35-safe). */
    public static final String HB_PLACEHOLDER = "DCRE" + "0".repeat(29);

    private ManReportLayout() {
    }

    public static String fileName(final String client, final String window) {
        return client + FILE_INFIX + window + FILE_SUFFIX;
    }

    public static String header(final String client, final String window) {
        return "MSD|%s|%s".formatted(client, window);
    }

    public static String detail(final String mandateRef, final String state) {
        return "%s%s|%s".formatted(DETAIL_PREFIX, mandateRef, state);
    }

    public static String heartbeatLine() {
        return "HB|%s".formatted(HB_PLACEHOLDER);
    }

    public static String trailer(final long count) {
        return "END|%d".formatted(count);
    }
}
