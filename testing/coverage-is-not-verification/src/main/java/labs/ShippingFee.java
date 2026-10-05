package labs;

/** 运费计算：满 99 元包邮；首重 1 公斤 8 元，续重每 500 克 2 元、不足 500 克按 500 克算；会员八折。金额单位是分，重量单位是克。 */
public final class ShippingFee {
    private ShippingFee() {
    }

    public static long fee(long amountCents, int weightGrams, boolean member) {
        if (amountCents < 0 || weightGrams <= 0) {
            throw new IllegalArgumentException("invalid input");
        }
        if (amountCents >= 9900) {
            return 0;
        }
        long fee = 800;
        if (weightGrams > 1000) {
            int extra = (weightGrams - 1000 + 499) / 500;
            fee += extra * 200L;
        }
        if (member) {
            fee = fee * 80 / 100;
        }
        return fee;
    }
}
