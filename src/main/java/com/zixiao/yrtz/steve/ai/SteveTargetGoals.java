package com.zixiao.yrtz.steve.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import com.zixiao.yrtz.steve.baseEntity.SteveEntity;
import com.zixiao.yrtz.steve.spSteve.endersummonerSteve.endersummonerSteveEntity;
import com.zixiao.yrtz.steve.spSteve.xmasSteve.xmasSteveEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Evoker;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;

/**
 * 统一的双向目标注册工具：
 * 「史蒂夫 → 袭击者」由 {@link #addIllagerTargets} 负责，
 * 「袭击者 / 亡灵 → 史蒂夫」由 {@link #addSteveTargets} / {@link #addSteveTarget} 负责。
 */
public final class SteveTargetGoals {

    /**
     * 史蒂夫 → 袭击者的目标优先级阶梯（数值越小越优先）。
     * <p>
     * 必须互不相同，原因在 {@code GoalSelector} 的仲裁机制：
     * <ul>
     *   <li>{@code availableGoals} 是 {@code LinkedHashSet}，tick 时按<b>插入顺序</b>遍历，并不按优先级排序；</li>
     *   <li>抢占判定 {@code WrappedGoal.canBeReplacedBy} 要求「新目标优先级<b>严格小于</b>当前目标」，
     *       同优先级之间永远无法互相顶替。</li>
     * </ul>
     * 因此若两种目标同为优先级 4，谁先抢到 {@code TARGET} 标记就会一直占着，
     * 而 {@code NearestAttackableTargetGoal} 每 10 tick 才探测一次，最终胜出者取决于随机时机 —— 不可控。
     * 给定互不相同的数值后，史蒂夫面对唤魔者时总会被优先级更小的唤魔者目标抢占成功。
     * <p>
     * 全部数值仍低于史蒂夫原有的 Player(2) 与 Zombie(3)，所以原有仇恨链不受影响。
     */
    public static final int PRIORITY_EVOKER = 4;      // 唤魔者：召唤恼鬼/法术，威胁最高，最先处理
    public static final int PRIORITY_VINDICATOR = 5;  // 卫道士：高额近战
    public static final int PRIORITY_PILLAGER = 6;    // 掠夺者：弩远程
    public static final int PRIORITY_RAVAGER = 7;     // 劫掠兽：高血量近战
    public static final int PRIORITY_VEX = 8;         // 恼鬼：脆弱且多为唤魔者的产物，最后处理

    /** 史蒂夫原有目标：玩家。与各子类 registerGoals 里的 addGoal(2, Player) 对齐。 */
    public static final int PRIORITY_PLAYER = 2;

    /** 史蒂夫原有目标：僵尸及其子类（尸壳/溺尸/僵尸村民）。与 addGoal(3, Zombie) 对齐。 */
    public static final int PRIORITY_ZOMBIE = 3;

    /** 不属于袭击者家族时的阶梯值，用于统一比较（数值越大越不优先）。 */
    public static final int NOT_ILLAGER = Integer.MAX_VALUE;

    /**
     * 袭击者 → 史蒂夫的目标优先级。
     * 原版这五种袭击者的目标选择器最高只用到 4（卫道士的 Johnny 目标、劫掠兽的村民/铁傀儡目标），
     * 因此取 5 严格低于它们全部：顺序确定，不与任何原有目标同优先级竞争。
     */
    public static final int STEVE_PRIORITY = 5;

    private SteveTargetGoals() {
    }

    /**
     * 为史蒂夫新增袭击者目标：唤魔者、卫道士、掠夺者、劫掠兽、恼鬼。
     * 优先级按 {@link #PRIORITY_EVOKER} … {@link #PRIORITY_VEX} 严格递增，
     * 因此「同时面对唤魔者与恼鬼时必定先打唤魔者」，与插入顺序、随机探测时机无关。
     * <p>
     * 同时做三件事：
     * <ul>
     *   <li>把各史蒂夫自己注册的原版 {@link HurtByTargetGoal} 升级为 {@link SteveRetaliationGoal}：
     *       否则一旦先被恼鬼打中，优先级 1 的反击目标会占住 {@code TARGET} 标记，
     *       上述优先级阶梯将全部失效；</li>
     *   <li>挂上 {@link SteveTargetWatchdogGoal}：负责「骑乘者改打载具」与「打不到就换目标」；</li>
     *   <li>给五个袭击者目标加过滤：已被判定打不到的目标、以及骑在可攻击载具上的骑手都不再被选中
     *       （典型场景是掠夺者骑着劫掠兽，此时应打劫掠兽，而不是永远够不着的骑手）。</li>
     * </ul>
     * 必须在目标选择器初始化之后调用（实体构造期或 registerGoals 内部均可）。
     */
    public static void addIllagerTargets(Mob mob) {
        SteveTargetWatchdogGoal watchdog = new SteveTargetWatchdogGoal(mob);

        // 无 Flag 的哨兵目标：不参与 GoalSelector 的仲裁，只做目标纠偏
        mob.goalSelector.addGoal(0, watchdog);

        upgradeRetaliationGoal(mob, watchdog);

        Predicate<LivingEntity> filter = entity -> !watchdog.isBlocked(entity)
                && attackableVehicle(mob, entity) == null;

        addIllagerTarget(mob, Evoker.class, PRIORITY_EVOKER, filter);
        addIllagerTarget(mob, Vindicator.class, PRIORITY_VINDICATOR, filter);
        addIllagerTarget(mob, Pillager.class, PRIORITY_PILLAGER, filter);
        addIllagerTarget(mob, Ravager.class, PRIORITY_RAVAGER, filter);
        addIllagerTarget(mob, Vex.class, PRIORITY_VEX, filter);
    }

    private static <T extends LivingEntity> void addIllagerTarget(
            Mob mob, Class<T> type, int priority, Predicate<LivingEntity> filter) {
        mob.targetSelector.addGoal(priority, new NearestAttackableTargetGoal<>(mob, type, true, filter));
    }

    /**
     * 若 {@code candidate} 是史蒂夫本就攻击的袭击者、且正骑在史蒂夫可以攻击的载具上，返回该载具，否则返回 {@code null}。
     * <p>
     * 典型场景：掠夺者骑着劫掠兽射史蒂夫。史蒂夫按「谁打我我打谁」锁定骑手，
     * 但骑手坐在劫掠兽背上，垂直高度差让近战判定（{@code getAttackReachSqr}）永远不足，
     * 于是史蒂夫贴着劫掠兽原地僵持、一下也打不到。此时应改打载具，打完骑手自然落地成可选中目标。
     * <p>
     * 只对袭击者生效：骑马的玩家等其它骑乘关系不受影响，史蒂夫仍按原逻辑锁定骑手。
     */
    @Nullable
    public static LivingEntity attackableVehicle(Mob attacker, LivingEntity candidate) {
        if (!candidate.isPassenger() || illagerRank(candidate) == NOT_ILLAGER) {
            return null;
        }
        Entity vehicle = candidate.getVehicle();
        if (!(vehicle instanceof LivingEntity livingVehicle) || !livingVehicle.isAlive()) {
            return null;
        }
        return attacker.canAttack(livingVehicle) ? livingVehicle : null;
    }

    /**
     * 史蒂夫攻击目标的优先级顺序，与 {@code addGoal} 的优先级数值一致（越小越优先）：
     * 玩家(2) → 僵尸(3) → 唤魔者(4) → 卫道士(5) → 掠夺者(6) → 劫掠兽(7) → 恼鬼(8)。
     * 非史蒂夫攻击目标返回 {@link #NOT_ILLAGER}。
     */
    public static int attackPriorityOf(@Nullable LivingEntity entity) {
        if (entity instanceof Player) {
            return PRIORITY_PLAYER;
        }
        if (entity instanceof Zombie) {
            return PRIORITY_ZOMBIE;
        }
        return illagerRank(entity);
    }

    /**
     * 把实体已注册的原版 {@link HurtByTargetGoal} 原位替换为 {@link SteveRetaliationGoal}，
     * 保留其原有优先级，因此反击的整体时序（第 1 档）不变。
     * <p>
     * 只匹配精确类 {@code HurtByTargetGoal.class}，已经是 {@link SteveRetaliationGoal} 的不会重复替换。
     */
    private static void upgradeRetaliationGoal(Mob mob, SteveTargetWatchdogGoal watchdog) {
        if (!(mob instanceof PathfinderMob pathfinder)) {
            return;
        }

        GoalSelector selector = mob.targetSelector;

        List<WrappedGoal> legacy = new ArrayList<>();
        for (WrappedGoal wrapped : selector.getAvailableGoals()) {
            if (wrapped.getGoal().getClass() == HurtByTargetGoal.class) {
                legacy.add(wrapped);
            }
        }

        for (WrappedGoal wrapped : legacy) {
            selector.removeGoal(wrapped.getGoal());
            selector.addGoal(wrapped.getPriority(), new SteveRetaliationGoal(pathfinder, watchdog));
        }
    }

    /**
     * 袭击者在史蒂夫眼中的优先级阶梯值，非袭击者返回 {@link #NOT_ILLAGER}。
     * 数值越小越优先，恰好与 {@code addGoal} 的优先级语义一致。
     */
    public static int illagerRank(@Nullable LivingEntity entity) {
        if (entity instanceof Evoker) {
            return PRIORITY_EVOKER;
        }
        if (entity instanceof Vindicator) {
            return PRIORITY_VINDICATOR;
        }
        if (entity instanceof Pillager) {
            return PRIORITY_PILLAGER;
        }
        if (entity instanceof Ravager) {
            return PRIORITY_RAVAGER;
        }
        if (entity instanceof Vex) {
            return PRIORITY_VEX;
        }
        return NOT_ILLAGER;
    }

    /**
     * 为唤魔者 / 卫道士 / 掠夺者 / 劫掠兽 / 恼鬼新增攻击史蒂夫的目标，
     * 覆盖 SteveEntity 全部子类，以及直接继承 Monster 的末影召唤者史蒂夫与圣诞史蒂夫。
     */
    public static void addSteveTargets(Mob mob) {
        addSteveTarget(mob, SteveEntity.class, STEVE_PRIORITY);
        addSteveTarget(mob, endersummonerSteveEntity.class, STEVE_PRIORITY);
        addSteveTarget(mob, xmasSteveEntity.class, STEVE_PRIORITY);
    }

    /**
     * 为亡灵等其他敌对实体新增史蒂夫目标，优先级由调用方决定（溺尸沿用僵尸的 2）。
     */
    public static <T extends LivingEntity> void addSteveTarget(
            Mob mob, Class<T> steveType, int priority) {
        mob.targetSelector.addGoal(priority, new NearestAttackableTargetGoal<>(mob, steveType, true));
    }
}
