package org.examplea.annihilationblade.combat;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class TerminationSavedData extends SavedData {
    private static final String DATA_NAME = "annihilationblade_terminations";
    private static final Factory<TerminationSavedData> FACTORY =
            new Factory<>(TerminationSavedData::new, TerminationSavedData::load);
    private final Map<UUID, Entry> entries = new HashMap<>();

    static TerminationSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    static TerminationSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        TerminationSavedData data = new TerminationSavedData();
        ListTag records = tag.getList("Records", Tag.TAG_COMPOUND);
        for (Tag element : records) {
            CompoundTag record = (CompoundTag) element;
            try {
                Entry entry = new Entry(
                        record.hasUUID("Request") ? record.getUUID("Request") : UUID.randomUUID(),
                        record.getUUID("HitTarget"), record.getUUID("Target"), record.getUUID("Attacker"),
                        TerminationContext.valueOf(record.getString("Context")), record.getString("Type"),
                        record.getString("Adapter"),
                        Phase.valueOf(record.getString("Phase")), record.getLong("StartedAt"),
                        record.getLong("Deadline"), record.getLong("ExpiresAt"), record.getLong("LastProgressAt"),
                        record.getString("Failure"), record.getBoolean("LootDropped"), record.getDouble("RemovedX"), record.getDouble("RemovedY"), record.getDouble("RemovedZ"));
                data.entries.put(entry.targetId(), entry);
            } catch (RuntimeException ignored) {
            }
        }
        return data;
    }

    boolean add(Entry entry) {
        if (entries.putIfAbsent(entry.targetId(), entry) != null) return false;
        setDirty();
        return true;
    }

    Entry get(UUID targetId) {
        return entries.get(targetId);
    }

    List<Entry> snapshot() {
        return new ArrayList<>(entries.values());
    }

    void update(Entry entry) {
        entries.put(entry.targetId(), entry);
        setDirty();
    }

    void remove(UUID targetId) {
        if (entries.remove(targetId) != null) setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag records = new ListTag();
        for (Entry entry : entries.values()) {
            CompoundTag record = new CompoundTag();
            record.putUUID("Request", entry.requestId());
            record.putUUID("HitTarget", entry.hitTargetId());
            record.putUUID("Target", entry.targetId());
            record.putUUID("Attacker", entry.attackerId());
            record.putString("Context", entry.context().name());
            record.putString("Type", entry.typeId());
            record.putString("Adapter", entry.adapterId());
            record.putString("Phase", entry.phase().name());
            record.putLong("StartedAt", entry.startedAt());
            record.putLong("Deadline", entry.deadline());
            record.putLong("ExpiresAt", entry.expiresAt());
            record.putLong("LastProgressAt", entry.lastProgressAt());
            record.putString("Failure", entry.failure());
            record.putBoolean("LootDropped", entry.lootDropped());
            record.putDouble("RemovedX", entry.removedX());
            record.putDouble("RemovedY", entry.removedY());
            record.putDouble("RemovedZ", entry.removedZ());
            records.add(record);
        }
        tag.put("Records", records);
        return tag;
    }

    enum Phase {
        REQUESTED, RUNNING, SUCCEEDED, FORCE_CLEARED, NUCLEAR_REMOVED, FAILED
    }

    record Entry(UUID requestId, UUID hitTargetId, UUID targetId, UUID attackerId,
                 TerminationContext context, String typeId, String adapterId, Phase phase, long startedAt,
                 long deadline, long expiresAt, long lastProgressAt, String failure, boolean lootDropped, double removedX, double removedY, double removedZ) {
        Entry withPhase(Phase next, long progressAt, String reason) {
            return new Entry(requestId, hitTargetId, targetId, attackerId, context, typeId, adapterId,
                    next, startedAt, deadline, expiresAt, progressAt, reason, lootDropped, removedX, removedY, removedZ);
        }

        Entry withRemovalPosition(double x, double y, double z) {
            return new Entry(requestId, hitTargetId, targetId, attackerId, context, typeId, adapterId,
                    phase, startedAt, deadline, expiresAt, lastProgressAt, failure, lootDropped, x, y, z);
        }

        Entry withLootDropped(boolean dropped) {
            return new Entry(requestId, hitTargetId, targetId, attackerId, context, typeId, adapterId,
                    phase, startedAt, deadline, expiresAt, lastProgressAt, failure, dropped, removedX, removedY, removedZ);
        }
    }
}
