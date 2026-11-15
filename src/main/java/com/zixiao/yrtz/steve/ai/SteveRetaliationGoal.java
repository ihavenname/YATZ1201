package com.zixiao.yrtz.steve.ai;

import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.monster.Evoker;
import net.minecraft.world.phys.AABB;

/**
 * 史蒂夫专用的反击目标，替换原版 {@link HurtByTargetGoal}。
 *
 * <p>问题根源：原版反击目标注册在优先级 1，而史蒂夫的袭击者目标注册在 4~8。
 * {@code GoalSelector} 的抢占规则是「新目标优先级必须严格小于当前目标」，
 * 因此一旦史蒂夫被恼鬼打了一下，反击目标立刻拿到 {@code TARGET} 标记并长期占位，
 * 优先级更高的唤魔者目标根本没有机会启动——这与 {@link SteveTargetGoals} 里的阶梯布局相反。
 *
 * <p>修法：保持「被谁打就反击谁」的原版语义，只给<b>唤魔者</b>一个特权 ——
 * 当前反击目标是袭击者、且范围内（跟随距离 + 视线）能打到唤魔者时，改锁唤魔者。
 * 例如被恼鬼偷袭时，若范围内有唤魔者，史蒂夫会立刻转火唤魔者。
 *
 * <p>其余袭击者（卫道士 / 掠夺者 / 劫掠兽 / 恼鬼）之间仍严格「谁打我我打谁」，
 * 反击普通生物（玩家、僵尸等）时也不做任何干预，与原版完全一致。
 */
public class SteveRetaliationGoal extends HurtByTargetGoal {

    /** 转火扫描间隔（tick）。每 tick 检索附近实体没必要，10 tick 一次足够跟手。 */
    private static final int SCAN_INTERVAL = 10;

    private int scanCooldown;

    private final SteveTargetWatchdogGoal watchdog;

    public SteveRetaliationGoal(PathfinderMob mob, SteveTargetWatchdogGoal watchdog) {
        super(mob);
        this.watchdog = watchdog;
    }

    @Override
    public void start() {
        super.start();
        // 被打的瞬间就判定一次，保证第一时间转火，而不是等 10 tick
        this.scanCooldown = 0;
        this.yrtz$preferEvoker();
    }

    @Override
    public boolean canContinueToUse() {
        if (!super.canContinueToUse()) {
            return false;
        }

        if (--this.scanCooldown <= 0) {
            this.scanCooldown = SCAN_INTERVAL;
            this.yrtz$preferEvoker();
        }

        return true;
    }

    /**
     * 仅当当前目标是袭击者、且范围内存在可攻击的唤魔者时，把目标换成唤魔者。
     * 其余情况一律保留原版反击目标（谁打我我打谁）。
     */
    private void yrtz$preferEvoker() {
        LivingEntity current = this.mob.getTarget();
        if (current == null) {
            current = this.targetMob;
        }

        int currentRank = SteveTargetGoals.illagerRank(current);

        // 当前目标不是袭击者（玩家/僵尸等），或已经是唤魔者：不干预原版反击逻辑
        if (currentRank == SteveTargetGoals.NOT_ILLAGER || currentRank == SteveTargetGoals.PRIORITY_EVOKER) {
            return;
        }

        Evoker evoker = this.yrtz$findEvoker();
        if (evoker != null) {
            this.mob.setTarget(evoker);
            this.targetMob = evoker;
        }
    }

    /**
     * 在跟随距离内寻找离得最近、且当前可攻击（含视线判定）的唤魔者。
     */
    @Nullable
    private Evoker yrtz$findEvoker() {
        double range = this.getFollowDistance();
        AABB area = this.mob.getBoundingBox().inflate(range, 4.0D, range);
        TargetingConditions conditions = TargetingConditions.forCombat().range(range);

        List<Evoker> candidates = this.mob.level().getEntitiesOfClass(Evoker.class, area,
                evoker -> !this.watchdog.isBlocked(evoker) && this.canAttack(evoker, conditions));

        return candidates.stream()
                .min(Comparator.comparingDouble(this.mob::distanceToSqr))
                .orElse(null);
    }
}
