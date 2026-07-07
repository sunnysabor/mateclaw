package vip.mate.points.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.points.model.PointsAccountEntity;
import vip.mate.points.model.PointsDtos;
import vip.mate.points.model.PointsLedgerEntity;
import vip.mate.points.service.PointsService;

import java.util.List;

@Tag(name = "用户积分")
@RestController
@RequestMapping("/api/v1/points")
@RequiredArgsConstructor
public class PointsController {
    private final PointsService pointsService;
    private final AuthService authService;

    @Operation(summary = "我的积分概览")
    @GetMapping("/summary")
    public R<PointsDtos.PointsSummary> summary(Authentication auth,
                                               @RequestParam(defaultValue = "50") int limit) {
        UserEntity user = requireUser(auth);
        return R.ok(pointsService.summary(user.getId(), limit));
    }

    @Operation(summary = "我的积分账户")
    @GetMapping("/account")
    public R<PointsAccountEntity> account(Authentication auth) {
        UserEntity user = requireUser(auth);
        return R.ok(pointsService.getAccount(user.getId()));
    }

    @Operation(summary = "我的积分流水")
    @GetMapping("/ledger")
    public R<List<PointsLedgerEntity>> ledger(Authentication auth,
                                             @RequestParam(defaultValue = "50") int limit) {
        UserEntity user = requireUser(auth);
        return R.ok(pointsService.listLedger(user.getId(), limit));
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
