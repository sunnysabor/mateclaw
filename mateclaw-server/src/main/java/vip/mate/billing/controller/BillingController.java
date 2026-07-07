package vip.mate.billing.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.billing.model.BillingDtos;
import vip.mate.billing.model.BillingLedgerEntity;
import vip.mate.billing.model.BillingOrderEntity;
import vip.mate.billing.model.BillingPackageEntity;
import vip.mate.billing.service.BillingService;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;

import java.util.List;

@Tag(name = "充值付费")
@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor
public class BillingController {
    private final BillingService billingService;
    private final AuthService authService;

    @Operation(summary = "我的充值概览")
    @GetMapping("/summary")
    public R<BillingDtos.BillingSummary> summary(Authentication auth) {
        UserEntity user = requireUser(auth);
        return R.ok(billingService.summary(user.getId()));
    }

    @Operation(summary = "可用充值套餐")
    @GetMapping("/packages")
    public R<List<BillingPackageEntity>> packages() {
        return R.ok(billingService.listEnabledPackages());
    }

    @Operation(summary = "我的充值订单")
    @GetMapping("/orders")
    public R<List<BillingOrderEntity>> orders(Authentication auth,
                                             @RequestParam(defaultValue = "50") int limit) {
        UserEntity user = requireUser(auth);
        return R.ok(billingService.listOrders(user.getId(), limit));
    }

    @Operation(summary = "我的钱包流水")
    @GetMapping("/ledger")
    public R<List<BillingLedgerEntity>> ledger(Authentication auth,
                                              @RequestParam(defaultValue = "50") int limit) {
        UserEntity user = requireUser(auth);
        return R.ok(billingService.listLedger(user.getId(), limit));
    }

    @Operation(summary = "创建充值订单（本地模拟支付）")
    @PostMapping("/orders")
    public R<BillingOrderEntity> createOrder(@RequestBody BillingDtos.CreateOrderRequest request,
                                             Authentication auth) {
        UserEntity user = requireUser(auth);
        return R.ok(billingService.createOrder(user.getId(), request));
    }

    @Operation(summary = "完成本地模拟支付并入账")
    @PostMapping("/orders/{id}/mock-pay")
    public R<BillingOrderEntity> mockPay(@PathVariable Long id, Authentication auth) {
        UserEntity user = requireUser(auth);
        return R.ok(billingService.mockPay(user.getId(), id));
    }

    @Operation(summary = "取消我的待支付订单")
    @PostMapping("/orders/{id}/cancel")
    public R<BillingOrderEntity> cancel(@PathVariable Long id, Authentication auth) {
        UserEntity user = requireUser(auth);
        return R.ok(billingService.cancelOrder(user.getId(), id));
    }

    private UserEntity requireUser(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new MateClawException("err.auth.unauthenticated", 401, "Authentication required");
        }
        UserEntity user = authService.findByUsername(auth.getName());
        if (user == null || (user.getDeleted() != null && user.getDeleted() != 0)) {
            throw new MateClawException("err.auth.user_not_found", 404, "用户不存在");
        }
        return user;
    }
}
