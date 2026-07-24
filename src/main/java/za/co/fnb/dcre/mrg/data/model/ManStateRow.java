package za.co.fnb.dcre.mrg.data.model;

/** Read projection of one mandate selected for state-delta emission: current (mandate_ref, state). */
public record ManStateRow(String mandateRef, String state) {
}
