package com.zixiao.yrtz.mixin.mobattacksteve;

import com.zixiao.yrtz.steve.ai.SteveTargetGoals;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Evoker;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 让唤魔者、卫道士、掠夺者、劫掠兽、恼鬼主动攻击模组内的史蒂夫实体
 * （SteveEntity 全部子类 + endersummonerSteveEntity + xmasSteveEntity）。
 * <p>
 * 为什么挂在 {@link Mob} 的构造器返回处，而不是分别去改五个类的 registerGoals：
 * 这五个类各自的 registerGoals 只会被自己调用、且目标选择器内容各不相同，
 * 逐个写 Mixin 需要五份几乎相同的注入；而它们全都经由 super 链走到 {@code Mob} 的构造器，
 * 且 {@code Mob} 构造器在末尾已经调用了 {@code registerGoals()}（仅服务端），
 * 因此在构造器 RETURN 处追加目标，等价于「在各自 registerGoals 之后追加」，一处即可覆盖。
 * 这里用 instanceof 精确限定到这五种实体，其它任何生物都不会被影响。
 * <p>
 * 优先级 5 严格低于它们原有的全部目标（唤魔者/掠夺者/恼鬼最高 3，卫道士/劫掠兽最高 4），
 * 因此原有「玩家 > 村民/铁傀儡」的仇恨顺序完全不变，只有在找不到原目标时才会转而攻击史蒂夫；
 * 史蒂夫一旦反击，优先级 1 的 HurtByTargetGoal 仍会立刻接管。
 */
@Mixin(Mob.class)
public abstract class MobAttackSteveMixin {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void yrtz$addSteveTargets(EntityType<?> type, Level level, CallbackInfo ci) {
        Mob self = (Mob) (Object) this;

        // 客户端不注册，避免无意义的目标计算（与原版 registerGoals 的服务端守卫一致）
        if (level == null || level.isClientSide) {
            return;
        }

        if (self instanceof Evoker
                || self instanceof Vindicator
                || self instanceof Pillager
                || self instanceof Ravager
                || self instanceof Vex) {
            SteveTargetGoals.addSteveTargets(self);
        }
    }
}
