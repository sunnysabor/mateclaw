package vip.mate.billing.model;

import java.util.List;

public final class BillingDtos {
    private BillingDtos() {}

    public record CreateOrderRequest(Long packageId, String paymentMethod) {}

    public record ManualCreditRequest(Long userId, Long amountCents, String remark) {}

    public record BillingSummary(
            BillingWalletEntity wallet,
            List<BillingPackageEntity> packages,
            List<BillingOrderEntity> orders,
            List<BillingLedgerEntity> ledger) {}
}
