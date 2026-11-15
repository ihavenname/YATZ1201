package com.zixiao.yrtz.mixin.drownedAttackSteve;

import com.zixiao.yrtz.steve.ai.SteveTargetGoals;
import com.zixiao.yrtz.steve.baseEntity.SteveEntity;
import com.zixiao.yrtz.steve.spSteve.endersummonerSteve.endersummonerSteveEntity;
import com.zixiao.yrtz.steve.spSteve.xmasSteve.xmasSteveEntity;
import net.minecraft.world.entity.monster.Drowned;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 让溺尸主动攻击模组内的史蒂夫实体。
 * <p>
 * 为什么不能用现成的 ZombieMixin：
 * {@code Zombie#addBehaviourGoals} 被 ZombieMixin 整体 @Overwrite，
 * 但 {@code Drowned} 自己重写了 {@code addBehaviourGoals} 且<b>没有</b>调用 {@code super.addBehaviourGoals()}，
 * 所以僵尸那套「攻击史蒂夫」的目标选择器不会继承到溺尸身上。
 * 这里在溺尸自己的 {@code addBehaviourGoals} 末尾追加目标，改动局限在溺尸，不影响其它亡灵。
 * <p>
 * 优先级 2 与 ZombieMixin 保持一致：史蒂夫优先于村民(3)、铁傀儡(3)、美西螈(3)等原有目标；
 * 溺尸原生的 Player 目标(优先级 2)已被 NearestAttackableTargetGoalMixin 取消，不会与之冲突。
 */
@Mixin(Drowned.class)
public abstract class DrownedMixin {

    @Inject(method = "addBehaviourGoals", at = @At("TAIL"))
    private void yrtz$addSteveTargets(CallbackInfo ci) {
        Drowned self = (Drowned) (Object) this;
        int priority = 2;

        SteveTargetGoals.addSteveTarget(self, SteveEntity.class, priority);
        SteveTargetGoals.addSteveTarget(self, endersummonerSteveEntity.class, priority);
        SteveTargetGoals.addSteveTarget(self, xmasSteveEntity.class, priority);
    }
}
