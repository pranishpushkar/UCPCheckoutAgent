package dev.ucplab.eval.interop;

/** One row of the sample merchant's {@code orders} table: its own record that a purchase happened (I1's ground truth). */
public record SampleOrder(String id, String checkoutId, long totalMinor) {
}
