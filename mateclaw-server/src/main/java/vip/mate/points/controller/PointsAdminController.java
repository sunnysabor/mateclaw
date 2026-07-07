package vip.mate.points.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.points.model.PointsAccountEntity;
import vip.mate.points.model.PointsDtos;
import vip.mate.points.service.PointsService;
import vip.mate.workspace.core.annotation.RequireGlobalAdmin;

@Tag(name = "用户积分 - 管理员")
@RestController
@RequestMapping("/api/v1/admin/points")
@RequiredArgsConstructor
public class PointsAdminController {
    private final PointsService pointsService;
    private final AuthService authService;

    @Operation(summary = "管理员手工调整用户积分")
    @PostMapping("/manual-adjust")
    @RequireGlobalAdmin
    public R<PointsAccountEntity> manualAdjust(@RequestBody PointsDtos.ManualAdjustRequest request,
                                                Authentication auth) {
        UserEntity operator = requireUser(auth);
        if (request == null || request.userId() == null) {
            throw new MateClawException("err.points.user_required", 400, "请选择调整用户");
        }
        UserEntity target = authService.findById(request.userId());
        if (target == null || (target.getDeleted() != null && target.getDeleted() != 0)) {
            throw new MateClawException("err.auth.user_not_found", 404, "用户不存在");
        }
        return R.ok(pointsService.manualAdjust(operator, request));
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
