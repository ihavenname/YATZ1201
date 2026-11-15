package com.zixiao.yrtz.steve.baseEntity;

import com.zixiao.yrtz.steve.ai.SteveTargetGoals;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

public class SteveEntity extends modMonster {

    public SteveEntity(EntityType<? extends modMonster> type, Level level) {
        super(type, level);

        // 各子类都会重写 registerGoals 且不会回调 super，因此在基类构造末尾统一追加强袭者目标，
        // 保证所有史蒂夫（普通 / 死亡 / 特种）都具备该行为。
        if (level != null && !level.isClientSide) {
            SteveTargetGoals.addIllagerTargets(this);
        }
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        if (target instanceof SteveEntity) {
            return false;
        }
        return super.canAttack(target);
    }

    @Override
    public boolean isAlliedTo(Entity entity) {
        if (entity instanceof SteveEntity) {
            return true;
        }
        return super.isAlliedTo(entity);
    }

}