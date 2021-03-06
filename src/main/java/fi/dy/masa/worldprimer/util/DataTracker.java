package fi.dy.masa.worldprimer.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;
import fi.dy.masa.worldprimer.WorldPrimer;
import fi.dy.masa.worldprimer.config.Configs;
import fi.dy.masa.worldprimer.reference.Reference;

public class DataTracker
{
    private static final DataTracker INSTANCE = new DataTracker();
    private File worldDir = new File(".");
    private final Map<Integer, Integer> dimensionLoadCounts = new HashMap<>();
    private final Map<UUID, PlayerData> playerData = new HashMap<>();
    private int serverStarts;
    private boolean dirty;

    public static DataTracker instance()
    {
        return INSTANCE;
    }

    public void serverStarted()
    {
        this.serverStarts++;
        this.dirty = true;
    }

    public void dimensionLoaded(int dimension)
    {
        Integer count = this.dimensionLoadCounts.get(dimension);

        if (count == null)
        {
            count = Integer.valueOf(1);
        }
        else
        {
            count = count + 1;
        }

        this.dimensionLoadCounts.put(dimension, count);

        this.dirty = true;
    }

    public int getDimensionLoadCount(int dimension)
    {
        Integer count = this.dimensionLoadCounts.get(dimension);
        return count != null ? count.intValue() : 0;
    }

    public int getServerStartCount()
    {
        return this.serverStarts;
    }

    public int getPlayerDataCount(EntityPlayer player, PlayerDataType type)
    {
        return this.getOrCreatePlayerData(player).getCount(type);
    }

    public int getPlayerDimensionEventCount(EntityPlayer player, int dimension, PlayerDimensionDataType type)
    {
        return this.getOrCreatePlayerData(player).getDimensionEventCount(dimension, type);
    }

    public void incrementPlayerDataCount(EntityPlayer player, PlayerDataType type)
    {
        this.getOrCreatePlayerData(player).incrementCount(type);
        this.dirty = true;
    }

    public void incrementPlayerDimensionEventCount(EntityPlayer player, int dimension, PlayerDimensionDataType type)
    {
        this.getOrCreatePlayerData(player).incrementDimensionEventCount(dimension, type);
        this.dirty = true;
    }

    private PlayerData getOrCreatePlayerData(EntityPlayer player)
    {
        PlayerData data = this.playerData.get(player.getUniqueID());

        if (data == null)
        {
            data = new PlayerData();
            this.playerData.put(player.getUniqueID(), data);
        }

        return data;
    }

    private void readFromNBT(NBTTagCompound nbt)
    {
        if (nbt == null || nbt.hasKey("DimLoadCounts", Constants.NBT.TAG_LIST) == false)
        {
            return;
        }

        this.dimensionLoadCounts.clear();

        NBTTagList tagList = nbt.getTagList("DimLoadCounts", Constants.NBT.TAG_COMPOUND);
        int tagCount = tagList.tagCount();

        for (int i = 0; i < tagCount; i++)
        {
            NBTTagCompound tag = tagList.getCompoundTagAt(i);
            this.dimensionLoadCounts.put(tag.getInteger("Dim"), tag.getInteger("Count"));
        }

        this.playerData.clear();
        tagList = nbt.getTagList("PlayerData", Constants.NBT.TAG_COMPOUND);
        tagCount = tagList.tagCount();

        for (int i = 0; i < tagCount; i++)
        {
            NBTTagCompound tag = tagList.getCompoundTagAt(i);
            UUID uuid = new UUID(tag.getLong("UUIDM"), tag.getLong("UUIDL"));
            PlayerData data = new PlayerData();
            data.readFromNBT(tag);
            this.playerData.put(uuid, data);
        }

        this.serverStarts = nbt.getInteger("ServerStarts");
    }

    private NBTTagCompound writeToNBT(@Nonnull NBTTagCompound nbt)
    {
        NBTTagList tagList = new NBTTagList();
        nbt.setTag("DimLoadCounts", tagList);

        for (Map.Entry<Integer, Integer> entry : this.dimensionLoadCounts.entrySet())
        {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("Dim", entry.getKey());
            tag.setInteger("Count", entry.getValue());
            tagList.appendTag(tag);
        }

        tagList = new NBTTagList();
        nbt.setTag("PlayerData", tagList);

        for (Map.Entry<UUID, PlayerData> entry : this.playerData.entrySet())
        {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setLong("UUIDM", entry.getKey().getMostSignificantBits());
            tag.setLong("UUIDL", entry.getKey().getLeastSignificantBits());
            entry.getValue().writeToNBT(tag);
            tagList.appendTag(tag);
        }

        nbt.setInteger("ServerStarts", this.serverStarts);

        return nbt;
    }

    @Nullable
    private File getDataDir(boolean createDirs)
    {
        File saveDir = this.worldDir;

        if (saveDir != null)
        {
            saveDir = new File(saveDir, Reference.MOD_ID);

            if (saveDir.exists() == false && (createDirs == false || saveDir.mkdirs() == false))
            {
                if (createDirs)
                {
                    WorldPrimer.logger.warn("Failed to create a directory for storing the data tracker file '{}'", saveDir.getPath());
                }

                return null;
            }

            return saveDir;
        }

        return null;
    }

    public void readFromDisk(File worldDir)
    {
        if (Configs.enableDataTracking == false)
        {
            return;
        }

        // Clear old data regardless of whether there is a data file
        this.dimensionLoadCounts.clear();
        this.serverStarts = 0;

        this.worldDir = worldDir;
        File file = new File(new File(worldDir, Reference.MOD_ID), "data_tracker.nbt");

        // Compatibility for reading from the old file - remove at some point
        if (file.exists() == false || file.isFile() == false)
        {
            file = new File(new File(worldDir, Reference.MOD_ID), "dim_loads.nbt");
        }

        try
        {
            if (file.exists() && file.isFile())
            {
                this.readFromNBT(CompressedStreamTools.readCompressed(new FileInputStream(file)));
            }
        }
        catch (Exception e)
        {
            WorldPrimer.logger.warn("Failed to read tracker data from file '{}'", file.getPath());
        }
    }

    public void writeToDisk()
    {
        if (this.dirty == false || Configs.enableDataTracking == false)
        {
            return;
        }

        try
        {
            File saveDir = this.getDataDir(true);

            if (saveDir != null)
            {
                File fileTmp  = new File(saveDir, "data_tracker.nbt.tmp");
                File fileReal = new File(saveDir, "data_tracker.nbt");
                CompressedStreamTools.writeCompressed(this.writeToNBT(new NBTTagCompound()), new FileOutputStream(fileTmp));

                if (fileReal.exists())
                {
                    fileReal.delete();
                }

                fileTmp.renameTo(fileReal);
                this.dirty = false;
            }
        }
        catch (Exception e)
        {
            WorldPrimer.logger.warn("Failed to write tracker data to file");
        }
    }

    private static class PlayerData
    {
        private int joinCount;
        private int quitCount;
        private int deathCount;
        private int respawnCount;
        private final Map<Integer, Integer> dimensionEnterCounts = new HashMap<Integer, Integer>();
        private final Map<Integer, Integer> dimensionLeaveCounts = new HashMap<Integer, Integer>();

        public int getCount(PlayerDataType type)
        {
            switch (type)
            {
                case JOIN:      return this.joinCount;
                case QUIT:      return this.quitCount;
                case DEATH:     return this.deathCount;
                case RESPAWN:   return this.respawnCount;
                default:        return 0;
            }
        }

        public void incrementCount(PlayerDataType type)
        {
            switch (type)
            {
                case JOIN:
                    this.joinCount++;
                    break;

                case QUIT:
                    this.quitCount++;
                    break;

                case DEATH:
                    this.deathCount++;
                    break;

                case RESPAWN:
                    this.respawnCount++;
                    break;

                default:
            }
        }

        public int getDimensionEventCount(int dimension, PlayerDimensionDataType type)
        {
            Map<Integer, Integer> map = type == PlayerDimensionDataType.ENTER ? this.dimensionEnterCounts : this.dimensionLeaveCounts;
            Integer count = map.get(dimension);
            return count != null ? count.intValue() : 0;
        }

        public void incrementDimensionEventCount(int dimension, PlayerDimensionDataType type)
        {
            Map<Integer, Integer> map = type == PlayerDimensionDataType.ENTER ? this.dimensionEnterCounts : this.dimensionLeaveCounts;
            Integer countOld = map.get(dimension);
            int countNew = countOld != null ? countOld + 1 : 1;
            map.put(dimension, countNew);
        }

        public void readFromNBT(NBTTagCompound nbt)
        {
            this.joinCount = nbt.getInteger("JoinCount");
            this.quitCount = nbt.getInteger("QuitCount");
            this.deathCount = nbt.getInteger("DeathCount");
            this.respawnCount = nbt.getInteger("RespawnCount");

            this.readDimensionEventData(nbt, "DimEnter", this.dimensionEnterCounts);
            this.readDimensionEventData(nbt, "DimLeave", this.dimensionLeaveCounts);
        }

        private void readDimensionEventData(NBTTagCompound nbt, String key, Map<Integer, Integer> map)
        {
            map.clear();

            if (nbt.hasKey(key, Constants.NBT.TAG_LIST))
            {
                NBTTagList tagList = nbt.getTagList(key, Constants.NBT.TAG_COMPOUND);
                final int tagCount = tagList.tagCount();

                for (int i = 0; i < tagCount; i++)
                {
                    NBTTagCompound tag = tagList.getCompoundTagAt(i);
                    map.put(tag.getInteger("Dim"), tag.getInteger("Count"));
                }
            }
        }

        public NBTTagCompound writeToNBT(@Nonnull NBTTagCompound nbt)
        {
            nbt.setInteger("JoinCount", this.joinCount);
            nbt.setInteger("QuitCount", this.quitCount);
            nbt.setInteger("DeathCount", this.deathCount);
            nbt.setInteger("RespawnCount", this.respawnCount);

            this.writeDimensionEventData(nbt, "DimEnter", this.dimensionEnterCounts);
            this.writeDimensionEventData(nbt, "DimLeave", this.dimensionLeaveCounts);

            return nbt;
        }

        private void writeDimensionEventData(NBTTagCompound nbt, String key, Map<Integer, Integer> map)
        {
            NBTTagList tagList = new NBTTagList();

            for (Map.Entry<Integer, Integer> entry : map.entrySet())
            {
                NBTTagCompound tag = new NBTTagCompound();
                tag.setInteger("Dim", entry.getKey());
                tag.setInteger("Count", entry.getValue());
                tagList.appendTag(tag);
            }

            nbt.setTag(key, tagList);
        }
    }

    public enum PlayerDataType
    {
        JOIN,
        QUIT,
        DEATH,
        RESPAWN,
        ENTER_DIM;
    }

    public enum PlayerDimensionDataType
    {
        ENTER,
        LEAVE;
    }
}
