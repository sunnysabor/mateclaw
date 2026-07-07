package vip.mate.points.model;

import java.util.List;

public final class PointsDtos {
    private PointsDtos() {}

    public record PointsSummary(
            PointsAccountEntity account,
            List<PointsLedgerEntity> ledger) {}

    public record ManualAdjustRequest(
            Long userId,
            Long amount,
            String reason,
            String remark) {}
}
