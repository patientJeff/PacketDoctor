package com.packetdoctor.mixin;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** The block entities a level ticks, counted by type for lag reports. */
@Mixin(Level.class)
public interface LevelTickersAccessor {
	@Accessor("blockEntityTickers")
	List<TickingBlockEntity> packetdoctor$blockEntityTickers();
}
