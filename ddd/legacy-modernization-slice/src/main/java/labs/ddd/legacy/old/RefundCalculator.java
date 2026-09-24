package labs.ddd.legacy.old;

/** 修缮式改造的第一步：在遗留代码里切出的接缝。 */
public interface RefundCalculator {

    int refundCents(int feeCents, long sessionStartMillis, long nowMillis);
}
