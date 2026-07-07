package vip.mate.points.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.auth.model.UserEntity;
import vip.mate.exception.MateClawException;
import vip.mate.points.model.PointsAccountEntity;
import vip.mate.points.model.PointsDtos;
import vip.mate.points.model.PointsLedgerEntity;
import vip.mate.points.repository.PointsAccountMapper;
import vip.mate.points.repository.PointsLedgerMapper;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class PointsService {
    private static final String DEFAULT_LEVEL = "normal";
    private static final String DIRECTION_CREDIT = "credit";
    private static final String DIRECTION_DEBIT = "debit";
    private static final String DIRECTION_ADJUST = "adjust";

    private final PointsAccountMapper accountMapper;
    private final PointsLedgerMapper ledgerMapper;

    @Transactional
    public PointsDtos.PointsSummary summary(Long userId, int limit) {
        return new PointsDtos.PointsSummary(ensureAccount(userId), listLedger(userId, limit));
    }

    @Transactional
    public PointsAccountEntity getAccount(Long userId) {
        return ensureAccount(userId);
    }

    public List<PointsLedgerEntity> listLedger(Long userId, int limit) {
        if (userId == null) {
            throw new MateClawException("err.points.user_required", 400, "用户不能为空");
        }
        int capped = Math.max(1, Math.min(limit, 100));
        return ledgerMapper.selectList(new LambdaQueryWrapper<PointsLedgerEntity>()
                .eq(PointsLedgerEntity::getUserId, userId)
                .orderByDesc(PointsLedgerEntity::getCreateTime)
                .last("LIMIT " + capped));
    }

    @Transactional
    public PointsAccountEntity manualAdjust(UserEntity operator, PointsDtos.ManualAdjustRequest request) {
        if (request == null || request.userId() == null) {
            throw new MateClawException("err.points.user_required", 400, "请选择调整用户");
        }
        long amount = nonNull(request.amount());
        if (amount == 0) {
            throw new MateClawException("err.points.amount_nonzero", 400, "积分调整数量不能为 0");
        }
        String reason = normalizeReason(request.reason(), amount);
        Long operatorId = operator == null ? null : operator.getId();
        String remark = request.remark();
        if (remark == null || remark.isBlank()) {
            String operatorName = operator == null ? "admin" : operator.getUsername();
            remark = "Manual points adjustment by " + operatorName;
        }
        return changePoints(request.userId(), amount, reason, "manual_adjust", null, remark, operatorId);
    }

    /**
     * Award points to a user. Kept as a public service API so future C-end events
     * such as registration, invitation, recharge, or daily sign-in can reuse the
     * same account/ledger semantics.
     */
    @Transactional
    public PointsAccountEntity earn(Long userId, long amount, String reason, String bizType, String bizId, String remark) {
        if (amount <= 0) {
            throw new MateClawException("err.points.amount_positive", 400, "积分数量必须大于 0");
        }
        return changePoints(userId, amount, blankToDefault(reason, "earn"), bizType, bizId, remark, null);
    }

    /** Consume points from a user account. */
    @Transactional
    public PointsAccountEntity spend(Long userId, long amount, String reason, String bizType, String bizId, String remark) {
        if (amount <= 0) {
            throw new MateClawException("err.points.amount_positive", 400, "积分数量必须大于 0");
        }
        return changePoints(userId, -amount, blankToDefault(reason, "spend"), bizType, bizId, remark, null);
    }

    private PointsAccountEntity changePoints(Long userId,
                                             long delta,
                                             String reason,
                                             String bizType,
                                             String bizId,
                                             String remark,
                                             Long operatorId) {
        if (userId == null) {
            throw new MateClawException("err.points.user_required", 400, "用户不能为空");
        }
        PointsAccountEntity account = ensureAccount(userId);
        long earnedDelta = delta > 0 ? delta : 0;
        long spentDelta = delta < 0 ? -delta : 0;
        int updated = accountMapper.applyDelta(account.getId(), delta, earnedDelta, spentDelta);
        if (updated != 1) {
            throw new MateClawException("err.points.insufficient_balance", 400, "积分余额不足");
        }
        account = accountMapper.selectById(account.getId());

        PointsLedgerEntity ledger = new PointsLedgerEntity();
        ledger.setAccountId(account.getId());
        ledger.setUserId(userId);
        ledger.setDirection(resolveDirection(delta, reason));
        ledger.setAmount(delta);
        ledger.setBalanceAfter(nonNull(account.getBalance()));
        ledger.setReason(trimToLength(blankToDefault(reason, delta >= 0 ? "earn" : "spend"), 64));
        ledger.setBizType(trimToLength(bizType, 64));
        ledger.setBizId(trimToLength(bizId, 128));
        ledger.setRemark(trimToLength(remark, 512));
        ledger.setOperatorId(operatorId);
        ledgerMapper.insert(ledger);
        return account;
    }

    private synchronized PointsAccountEntity ensureAccount(Long userId) {
        if (userId == null) {
            throw new MateClawException("err.points.user_required", 400, "用户不能为空");
        }
        PointsAccountEntity account = accountMapper.selectOne(new LambdaQueryWrapper<PointsAccountEntity>()
                .eq(PointsAccountEntity::getUserId, userId)
                .eq(PointsAccountEntity::getDeleted, 0)
                .last("LIMIT 1"));
        if (account != null) {
            return account;
        }
        PointsAccountEntity created = new PointsAccountEntity();
        created.setUserId(userId);
        created.setBalance(0L);
        created.setTotalEarned(0L);
        created.setTotalSpent(0L);
        created.setLevelCode(DEFAULT_LEVEL);
        created.setDeleted(0);
        try {
            accountMapper.insert(created);
            return created;
        } catch (RuntimeException duplicateOrRace) {
            account = accountMapper.selectOne(new LambdaQueryWrapper<PointsAccountEntity>()
                    .eq(PointsAccountEntity::getUserId, userId)
                    .eq(PointsAccountEntity::getDeleted, 0)
                    .last("LIMIT 1"));
            if (account != null) {
                return account;
            }
            throw duplicateOrRace;
        }
    }

    private String normalizeReason(String reason, long amount) {
        String normalized = reason == null || reason.isBlank()
                ? "admin_adjustment"
                : reason.trim().toLowerCase(Locale.ROOT);
        if (amount > 0 && "spend".equals(normalized)) return "admin_adjustment";
        if (amount < 0 && "earn".equals(normalized)) return "admin_adjustment";
        return normalized;
    }

    private String resolveDirection(long delta, String reason) {
        if ("admin_adjustment".equals(reason) || "adjust".equals(reason)) {
            return DIRECTION_ADJUST;
        }
        return delta >= 0 ? DIRECTION_CREDIT : DIRECTION_DEBIT;
    }

    private static long nonNull(Long value) {
        return value == null ? 0L : value;
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String trimToLength(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
