package com.williambl.lapiswarps

import dev.onyxstudios.cca.api.v3.world.WorldComponentFactoryRegistry
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.gamerule.v1.CustomGameRuleCategory
import net.fabricmc.fabric.api.gamerule.v1.GameRuleFactory
import net.fabricmc.fabric.api.gamerule.v1.GameRuleRegistry
import net.fabricmc.fabric.api.tag.TagRegistry
import net.minecraft.block.BlockState
import net.minecraft.block.DoorBlock
import net.minecraft.block.enums.DoubleBlockHalf
import net.minecraft.entity.EntityType
import net.minecraft.entity.LightningEntity
import net.minecraft.inventory.Inventory
import net.minecraft.item.Item
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.tag.BlockTags
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.registry.Registry
import net.minecraft.world.World
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.random.asKotlinRandom

val portalTag = TagRegistry.block(Identifier("lapiswarps:portal_blocks"))

val warpsGameruleCategory = CustomGameRuleCategory(Identifier("lapiswarps:lapiswarps"), Text.of("LapisWarps"))
val lightningGamerule = GameRuleRegistry.register("lapisWarpsCreateLightning", warpsGameruleCategory, GameRuleFactory.createBooleanRule(true))

@ExperimentalContracts
fun init() {
    UseBlockCallback.EVENT.register { player, world, hand, hitResult ->
        if (world.isClient)
            return@register ActionResult.PASS

        val component = LapisWarpsComponent.key.get(world)

        val blockState = world.getBlockState(hitResult.blockPos)
        if (blockState.isIn(BlockTags.DOORS) && blockState[DoorBlock.OPEN]) {
            val lowerDoorPos = getLowerDoorPos(hitResult.blockPos, blockState)
            val dir = blockState[DoorBlock.FACING].opposite

            if (lowerDoorPos != player.blockPos) return@register ActionResult.PASS

            if (!checkForMultiBlock(world, lowerDoorPos, dir)) return@register ActionResult.PASS

            val channel = getItems(world, lowerDoorPos, dir)

            val otherPortalPos = component.portals.get(channel)?.filterNot { it == lowerDoorPos }.run {
                if (this == null || this.isEmpty()) null else this.random(world.random.asKotlinRandom())
            }

            if (isValidPortal(world, otherPortalPos) && getItems(world, otherPortalPos, world.getBlockState(otherPortalPos)[DoorBlock.FACING].opposite) == channel) {
                world.setBlockState(otherPortalPos, world.getBlockState(otherPortalPos).with(DoorBlock.OPEN, true))
                (player as ServerPlayerEntity).teleport(world as ServerWorld, otherPortalPos.x+0.5, otherPortalPos.y.toDouble(), otherPortalPos.z+0.5, blockState[DoorBlock.FACING].opposite.asRotation()-world.getBlockState(otherPortalPos)[DoorBlock.FACING].opposite.asRotation() + player.yaw, player.pitch)
                createEffects(world, lowerDoorPos)
                createEffects(world, otherPortalPos)
            } else if (otherPortalPos != null) {
                component.portals.remove(channel, otherPortalPos)
            }

            component.portals.asMap().asSequence()
                    .filter { it.key != channel && it.value.contains(lowerDoorPos) }
                    .map { it.key }
                    .forEach { component.portals.remove(it, lowerDoorPos) }

            if (!component.portals.containsValue(lowerDoorPos)) component.portals.put(channel, lowerDoorPos)
        }

        return@register ActionResult.PASS
    }
}

fun registerWorldComponents(registry: WorldComponentFactoryRegistry) {
    registry.register(LapisWarpsComponent.key) { world -> LapisWarpsComponentImpl() }
}

fun createEffects(world: World, pos: BlockPos) {
    if (world.gameRules.getBoolean(lightningGamerule)) {
        val entity = LightningEntity(EntityType.LIGHTNING_BOLT, world)
        entity.setCosmetic(true)
        entity.setPos(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)
        world.spawnEntity(entity)
    } else {
        world.playSound(null, pos, SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.BLOCKS, 1f, 1f)
    }
}

@ExperimentalContracts
fun isValidPortal(world: World, pos: BlockPos?): Boolean {
    contract {
        returns(true) implies (pos != null)
    }
    if (pos == null)
        return false
    val blockState = world.getBlockState(pos)
    return blockState.isIn(BlockTags.DOORS) && checkForMultiBlock(world, getLowerDoorPos(pos, blockState), blockState[DoorBlock.FACING].opposite)
}

fun getLowerDoorPos(pos: BlockPos, blockState: BlockState): BlockPos =
        if (blockState[DoorBlock.HALF] == DoubleBlockHalf.LOWER)
            pos
        else
            pos.down()

fun getItems(world: World, pos: BlockPos, dir: Direction): Int {
    val be = world.getBlockEntity(pos.mutableCopy().move(Direction.UP, 2).move(dir.opposite))
    val result = mutableListOf<Item>()

    if (be is Inventory) {
        for (i in 0 until be.size()) {
            result.add(be.getStack(i).item)
        }
    }

    return result.asSequence().distinct().map { Registry.ITEM.getId(it).toString() }.reduceOrNull(String::plus)?.hashCode() ?: 0
}

/**
 * @param pos the position of the *lower* door block
 * @param dir the 'forwards' direction of the door
 */
fun checkForMultiBlock(world: World, pos: BlockPos, dir: Direction): Boolean {
    val mut = pos.mutableCopy()

    val left = dir.rotateLeft()
    val right = dir.rotateRight()

    return listOf(
            world.getBlockState(mut.move(Direction.DOWN)),
            world.getBlockState(mut.move(Direction.UP).move(left)),
            world.getBlockState(mut.move(Direction.UP)),
            world.getBlockState(mut.set(pos).move(right)),
            world.getBlockState(mut.move(Direction.UP)),
            world.getBlockState(mut.set(pos).move(Direction.UP, 2)),
            world.getBlockState(mut.set(pos).move(dir.opposite)),
            world.getBlockState(mut.move(Direction.UP))
    ).all { portalTag.contains(it.block) }
}