package vip.mate.billing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.auth.model.UserEntity;
import vip.mate.billing.model.BillingDtos;
import vip.mate.billing.model.BillingLedgerEntity;
import vip.mate.billing.model.BillingOrderEntity;
import vip.mate.billing.model.BillingPackageEntity;
import vip.mate.billing.model.BillingWalletEntity;
import vip.mate.billing.repository.BillingLedgerMapper;
import vip.mate.billing.repository.BillingOrderMapper;
import vip.mate.billing.repository.BillingPackageMapper;
import vip.mate.billing.repository.BillingWalletMapper;
import vip.mate.exception.MateClawException;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class BillingService {
    private static final String DEFAULT_CURRENCY = "CNY";
    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_PAID = "paid";
    private static final String METHOD_MOCK = "mock";
    private static final String METHOD_MANUAL = "manual";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter ORDER_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final BillingWalletMapper walletMapper;
    private final BillingPackageMapper packageMapper;
    private final BillingOrderMapper orderMapper;
    private final BillingLedgerMapper ledgerMapper;

    @Transactional
    public BillingDtos.BillingSummary summary(Long userId) {
        return new BillingDtos.BillingSummary(
                ensureWallet(userId),
                listEnabledPackages(),
                listOrders(userId, 20),
                listLedger(userId, 20));
    }

    public List<BillingPackageEntity> listEnabledPackages() {
        return packageMapper.selectList(new LambdaQueryWrapper<BillingPackageEntity>()
                .eq(BillingPackageEntity::getDeleted, 0)
                .eq(BillingPackageEntity::getEnabled, true)
                .orderByAsc(BillingPackageEntity::getSortOrder)
                .orderByAsc(BillingPackageEntity::getAmountCents));
    }

    public List<BillingOrderEntity> listOrders(Long userId, int limit) {
        int capped = Math.max(1, Math.min(limit, 100));
        return orderMapper.selectList(new LambdaQueryWrapper<BillingOrderEntity>()
                .eq(BillingOrderEntity::getUserId, userId)
                .eq(BillingOrderEntity::getDeleted, 0)
                .orderByDesc(BillingOrderEntity::getCreateTime)
                .last("LIMIT " + capped));
    }

    public List<BillingLedgerEntity> listLedger(Long userId, int limit) {
        int capped = Math.max(1, Math.min(limit, 100));
        return ledgerMapper.selectList(new LambdaQueryWrapper<BillingLedgerEntity>()
                .eq(BillingLedgerEntity::getUserId, userId)
                .orderByDesc(BillingLedgerEntity::getCreateTime)
                .last("LIMIT " + capped));
    }

    @Transactional
    public BillingOrderEntity createOrder(Long userId, BillingDtos.CreateOrderRequest request) {
        if (request == null || request.packageId() == null) {
            throw new MateClawException("err.billing.package_required", 400, "请选择充值套餐");
        }
        BillingPackageEntity pkg = packageMapper.selectOne(new LambdaQueryWrapper<BillingPackageEntity>()
                .eq(BillingPackageEntity::getId, request.packageId())
                .eq(BillingPackageEntity::getDeleted, 0)
                .eq(BillingPackageEntity::getEnabled, true)
                .last("LIMIT 1"));
        if (pkg == null) {
            throw new MateClawException("err.billing.package_not_found", 404, "充值套餐不存在或已下架");
        }

        String paymentMethod = normalizePaymentMethod(request.paymentMethod());
        BillingOrderEntity order = new BillingOrderEntity();
        order.setOrderNo(nextOrderNo());
        order.setUserId(userId);
        order.setPackageId(pkg.getId());
        order.setAmountCents(nonNull(pkg.getAmountCents()));
        order.setBonusCents(nonNull(pkg.getBonusCents()));
        order.setCurrency(blankToDefault(pkg.getCurrency(), DEFAULT_CURRENCY));
        order.setPaymentMethod(paymentMethod);
        order.setStatus(STATUS_PENDING);
        order.setDeleted(0);
        orderMapper.insert(order);
        return order;
    }

    @Transactional
    public BillingOrderEntity mockPay(Long userId, Long orderId) {
        BillingOrderEntity order = requireOwnOrder(userId, orderId);
        if (STATUS_PAID.equals(order.getStatus())) {
            return order;
        }
        if (!STATUS_PENDING.equals(order.getStatus())) {
            throw new MateClawException("err.billing.order_not_payable", 400, "订单当前状态不可支付");
        }
        if (!METHOD_MOCK.equals(order.getPaymentMethod())) {
            throw new MateClawException("err.billing.mock_only", 400, "当前订单不是本地模拟支付订单");
        }
        int updated = orderMapper.markOwnPendingPaid(orderId, userId);
        if (updated != 1) {
            throw new MateClawException("err.billing.order_not_payable", 400, "订单当前状态不可支付");
        }
        creditWallet(userId, orderId, nonNull(order.getAmountCents()) + nonNull(order.getBonusCents()),
                "recharge", "Mock payment completed");
        return requireOwnOrder(userId, orderId);
    }

    @Transactional
    public BillingOrderEntity cancelOrder(Long userId, Long orderId) {
        BillingOrderEntity order = requireOwnOrder(userId, orderId);
        if (!STATUS_PENDING.equals(order.getStatus())) {
            return order;
        }
        int updated = orderMapper.cancelOwnPending(orderId, userId);
        if (updated != 1) {
            throw new MateClawException("err.billing.order_not_cancellable", 400, "订单当前状态不可取消");
        }
        return requireOwnOrder(userId, orderId);
    }

    @Transactional
    public BillingWalletEntity manualCredit(UserEntity operator, BillingDtos.ManualCreditRequest request) {
        if (request == null || request.userId() == null) {
            throw new MateClawException("err.billing.user_required", 400, "请选择入账用户");
        }
        long amount = nonNull(request.amountCents());
        if (amount <= 0) {
            throw new MateClawException("err.billing.amount_positive", 400, "入账金额必须大于 0");
        }
        String remark = request.remark();
        if (remark == null || remark.isBlank()) {
            String operatorName = operator == null ? "admin" : operator.getUsername();
            remark = "Manual credit by " + operatorName;
        }
        return creditWallet(request.userId(), null, amount, "admin_adjustment", remark);
    }

    private BillingWalletEntity creditWallet(Long userId, Long orderId, long amountCents, String reason, String remark) {
        if (amountCents <= 0) {
            throw new MateClawException("err.billing.amount_positive", 400, "入账金额必须大于 0");
        }
        BillingWalletEntity wallet = ensureWallet(userId);
        int updated = walletMapper.incrementBalance(wallet.getId(), amountCents);
        if (updated != 1) {
            throw new MateClawException("err.billing.wallet_update_failed", "钱包余额更新失败");
        }
        wallet = walletMapper.selectById(wallet.getId());

        BillingLedgerEntity ledger = new BillingLedgerEntity();
        ledger.setWalletId(wallet.getId());
        ledger.setUserId(userId);
        ledger.setOrderId(orderId);
        ledger.setDirection("credit");
        ledger.setAmountCents(amountCents);
        ledger.setBalanceAfterCents(nonNull(wallet.getBalanceCents()));
        ledger.setReason(reason);
        ledger.setRemark(trimToLength(remark, 512));
        ledgerMapper.insert(ledger);
        return wallet;
    }

    private synchronized BillingWalletEntity ensureWallet(Long userId) {
        if (userId == null) {
            throw new MateClawException("err.billing.user_required", 400, "用户不能为空");
        }
        BillingWalletEntity wallet = walletMapper.selectOne(new LambdaQueryWrapper<BillingWalletEntity>()
                .eq(BillingWalletEntity::getUserId, userId)
                .eq(BillingWalletEntity::getDeleted, 0)
                .last("LIMIT 1"));
        if (wallet != null) {
            return wallet;
        }
        BillingWalletEntity created = new BillingWalletEntity();
        created.setUserId(userId);
        created.setBalanceCents(0L);
        created.setCurrency(DEFAULT_CURRENCY);
        created.setDeleted(0);
        try {
            walletMapper.insert(created);
            return created;
        } catch (RuntimeException duplicateOrRace) {
            wallet = walletMapper.selectOne(new LambdaQueryWrapper<BillingWalletEntity>()
                    .eq(BillingWalletEntity::getUserId, userId)
                    .eq(BillingWalletEntity::getDeleted, 0)
                    .last("LIMIT 1"));
            if (wallet != null) {
                return wallet;
            }
            throw duplicateOrRace;
        }
    }

    private BillingOrderEntity requireOwnOrder(Long userId, Long orderId) {
        if (orderId == null) {
            throw new MateClawException("err.billing.order_required", 400, "订单不能为空");
        }
        BillingOrderEntity order = orderMapper.selectOne(new LambdaQueryWrapper<BillingOrderEntity>()
                .eq(BillingOrderEntity::getId, orderId)
                .eq(BillingOrderEntity::getUserId, userId)
                .eq(BillingOrderEntity::getDeleted, 0)
                .last("LIMIT 1"));
        if (order == null) {
            throw new MateClawException("err.billing.order_not_found", 404, "订单不存在");
        }
        return order;
    }

    private String normalizePaymentMethod(String paymentMethod) {
        String method = paymentMethod == null || paymentMethod.isBlank()
                ? METHOD_MOCK
                : paymentMethod.trim().toLowerCase(Locale.ROOT);
        if (!METHOD_MOCK.equals(method) && !METHOD_MANUAL.equals(method)) {
            throw new MateClawException("err.billing.payment_method_invalid", 400, "不支持的支付方式");
        }
        return method;
    }

    private String nextOrderNo() {
        int random = RANDOM.nextInt(1_000_000);
        return "RCG" + LocalDateTime.now().format(ORDER_TIME) + String.format("%06d", random);
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
