package com.zixiao.yrtz.steve.ai;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.phys.AABB;

/**
 * 史蒂夫的目标监视器：一个<b>不带任何 {@code Goal.Flag}</b> 的常驻目标。
 *
 * <p>不带 Flag 是关键：{@code GoalSelector} 只在目标有 Flag 时才会把它记进 {@code lockedFlags}，
 * 所以它既不会抢走移动/攻击/目标标记，也不会被任何其它目标顶掉，
 * 相当于挂在 {@code goalSelector} 上的一个每 tick 回调，只负责<b>纠偏</b>：
 *
 * <ol>
 *   <li><b>骑乘者 → 载具</b>：目标骑在可攻击的载具上时（典型场景：掠夺者骑着劫掠兽射史蒂夫），
 *       史蒂夫按「谁打我我打谁」会锁定骑手，但骑手坐在劫掠兽背上、垂直高度差大到近战判定永远够不着，
 *       于是原地僵持。这里把目标改成载具本身，先打死劫掠兽；骑手被掀下来后自然恢复为可选中目标。</li>
 *   <li><b>卡死切换</b>：持续盯住当前目标，若既没有造成伤害、也没有拉近距离，
 *       超过 {@link #GIVE_UP_TICKS} 就判定「打不到」，把该目标短暂拉黑并改锁周围另一个可攻击目标。</li>
 * </ol>
 */
public class SteveTargetWatchdogGoal extends Goal {

    /** 距离采样间隔（tick）。 */
    private static final int SAMPLE_INTERVAL = 20;

    /** 连续多久没有任何「进展」就判定打不到（tick，200 = 10 秒）。 */
    private static final int GIVE_UP_TICKS = 200;

    /** 放弃某个目标后的拉黑时长（tick）。到期后允许重新尝试。 */
    private static final int BLACKLIST_TICKS = 200;

    /** 一个采样周期内至少要拉近这么多（平方距离）才算有进展。 */
    private static final double PROGRESS_DISTANCE_SQR = 0.25D;

    /** 血量变化判定阈值，避免浮点噪声被当成伤害。 */
    private static final float HEALTH_EPSILON = 1.0E-4F;

    private final Mob mob;

    /** 被判定为「打不到」的目标，及其解禁时间（{@code level.getGameTime()} 基准）。 */
    private final Map<UUID, Long> blacklist = new HashMap<>();

    @Nullable
    private UUID targetId;
    private float lastHealth;
    private double lastSampleDistSqr = Double.MAX_VALUE;
    private int noProgressTicks;
    private int sampleCooldown = SAMPLE_INTERVAL;
    private int cleanupCooldown = SAMPLE_INTERVAL;

    public SteveTargetWatchdogGoal(Mob mob) {
        this.mob = mob;
    }

    @Override
    public boolean canUse() {
        // 无 Flag 的哨兵目标，常驻运行
        return true;
    }

    /**
     * {@code Mob.serverAiStep} 每隔一 tick 才会调一次完整的 {@code GoalSelector.tick()}，
     * 另一 tick 只调 {@code tickRunningGoals(false)}——那时只有声明「每 tick 更新」的目标才会被 tick。
     * 不声明的话采样节奏会减半，判定窗口跟着翻倍。
     */
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        long now = this.mob.level().getGameTime();

        if (--this.cleanupCooldown <= 0) {
            this.cleanupCooldown = SAMPLE_INTERVAL;
            this.blacklist.values().removeIf(expireAt -> expireAt <= now);
        }

        LivingEntity target = this.mob.getTarget();
        if (target == null) {
            this.targetId = null;
            this.noProgressTicks = 0;
            this.sampleCooldown = SAMPLE_INTERVAL;
            return;
        }

        // 1) 目标是骑乘者：改打载具（否则近战判定够不着骑手，会永久僵持）
        LivingEntity vehicle = SteveTargetGoals.attackableVehicle(this.mob, target);
        if (vehicle != null) {
            if (!this.isBlocked(vehicle, now)) {
                this.retarget(vehicle);
            } else {
                // 载具也被判定打不到：直接换别的目标
                LivingEntity alternative = this.findAlternative(target);
                if (alternative != null) {
                    this.retarget(alternative);
                }
            }
            return;
        }

        // 2) 该目标已被拉黑（可能被别的目标重新选上）：能换就换
        if (this.isBlocked(target, now)) {
            LivingEntity alternative = this.findAlternative(target);
            if (alternative != null) {
                this.retarget(alternative);
            }
            return;
        }

        // 3) 换了目标：重置进度统计
        if (!target.getUUID().equals(this.targetId)) {
            this.resetProgress(target);
            return;
        }

        // 4) 目标掉血说明确实打中了，算作进展
        float health = target.getHealth();
        if (health < this.lastHealth - HEALTH_EPSILON) {
            this.noProgressTicks = 0;
        }
        this.lastHealth = health;

        if (--this.sampleCooldown > 0) {
            return;
        }
        this.sampleCooldown = SAMPLE_INTERVAL;

        double distSqr = this.mob.distanceToSqr(target);
        if (this.lastSampleDistSqr - distSqr > PROGRESS_DISTANCE_SQR) {
            this.noProgressTicks = 0;
        } else {
            this.noProgressTicks += SAMPLE_INTERVAL;
        }
        this.lastSampleDistSqr = distSqr;

        if (this.noProgressTicks < GIVE_UP_TICKS) {
            return;
        }

        LivingEntity alternative = this.findAlternative(target);
        if (alternative == null) {
            // 周围确实没有别的可攻击目标：继续尝试，1 秒后再判定一次
            this.noProgressTicks = GIVE_UP_TICKS - SAMPLE_INTERVAL * 5;
            return;
        }

        this.blacklist.put(target.getUUID(), now + BLACKLIST_TICKS);
        this.retarget(alternative);
    }

    /**
     * 该目标是否处在「打不到」的拉黑期内。
     */
    public boolean isBlocked(LivingEntity entity) {
        return this.isBlocked(entity, this.mob.level().getGameTime());
    }

    private boolean isBlocked(LivingEntity entity, long now) {
        Long expireAt = this.blacklist.get(entity.getUUID());
        return expireAt != null && expireAt > now;
    }

    private void retarget(LivingEntity next) {
        this.mob.setTarget(next);
        this.resetProgress(next);
    }

    private void resetProgress(LivingEntity target) {
        this.targetId = target.getUUID();
        this.lastHealth = target.getHealth();
        this.lastSampleDistSqr = this.mob.distanceToSqr(target);
        this.noProgressTicks = 0;
        this.sampleCooldown = SAMPLE_INTERVAL;
    }

    /**
     * 在跟随距离内寻找另一个可以打的目标，按史蒂夫既有的优先级顺序取：
     * 玩家(2) → 僵尸(3) → 唤魔者(4) → 卫道士(5) → 掠夺者(6) → 劫掠兽(7) → 恼鬼(8)，同档取最近。
     * 骑在可攻击载具上的目标会被跳过，因为它们同样打不到。
     */
    @Nullable
    private LivingEntity findAlternative(@Nullable LivingEntity exclude) {
        double range = this.mob.getAttributeValue(Attributes.FOLLOW_RANGE);
        AABB area = this.mob.getBoundingBox().inflate(range, 4.0D, range);
        TargetingConditions conditions = TargetingConditions.forCombat().range(range);

        List<LivingEntity> candidates = this.mob.level().getEntitiesOfClass(LivingEntity.class, area,
                entity -> entity != exclude
                        && SteveTargetGoals.attackPriorityOf(entity) != SteveTargetGoals.NOT_ILLAGER
                        && !this.isBlocked(entity)
                        && SteveTargetGoals.attackableVehicle(this.mob, entity) == null
                        && conditions.test(this.mob, entity));

        return candidates.stream()
                .min(Comparator.<LivingEntity>comparingInt(SteveTargetGoals::attackPriorityOf)
                        .thenComparingDouble(this.mob::distanceToSqr))
                .orElse(null);
    }
}
