package com.example.teleportmod;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.*;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

public class TeleportMod implements ModInitializer {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .registerTypeAdapter(BlockPos.class, new BlockPosSerializer())
        .create();

    private static final File CONFIG_FILE = new File("config/teleportmod/config.json");
    private static final File LANGUAGE_FILE = new File("config/teleportmod/language.txt");
    private static final File TELEPORT_DATA_FILE = new File("config/teleportmod/teleport_links.json");

    private static Map<String, BlockPos> teleportLinks = new HashMap<>();
    private static Map<String, String> languageStrings = new HashMap<>();

    private static int permissionLevel = 4; // Default permission level

    private BlockPos firstSignPos = null;

    @Override
    public void onInitialize() {
        loadConfig();
        loadLanguage();
        loadTeleportLinks();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Text message = Text.literal("[Teleport-mod] Loaded")
                    .styled(style -> style.withColor(0x00FF00).withBold(true));
            server.getPlayerManager().broadcast(message, false);
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            BlockPos blockPos = hitResult.getBlockPos();
            Block block = world.getBlockState(blockPos).getBlock();

            if (block == Blocks.OAK_SIGN || block == Blocks.OAK_WALL_SIGN) {
                return handleSignInteraction(player, world, blockPos);
            }

            return ActionResult.PASS;
        });

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (blockEntity instanceof SignBlockEntity sign) {
                String posKey = blockPosToString(pos);
                if (teleportLinks.containsKey(posKey)) {
                    if (!player.hasPermissionLevel(permissionLevel)) {
                        player.sendMessage(Text.literal(languageStrings.get("no_permission_to_destroy")), true);
                        if (player instanceof ServerPlayerEntity serverPlayer) {
                            serverPlayer.networkHandler.sendPacket(new BlockUpdateS2CPacket(world, pos));
                        }
                        return false;
                    } else {
                        removeTeleportLink(pos);
                    }
                }
            }
            return true;
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("teleportmod")
                .requires(source -> source.hasPermissionLevel(permissionLevel))
                .then(CommandManager.literal("reload")
                    .executes(context -> {
                        loadConfig();
                        loadLanguage();
                        context.getSource().sendFeedback(() -> Text.literal("[TeleportMod] Configuration and language files reloaded."), true);
                        return 1;
                    })
                )
            );
        });
    }

    private ActionResult handleSignInteraction(PlayerEntity player, World world, BlockPos blockPos) {
        // Controlla se il giocatore ha i permessi necessari (admin)
        if (player.hasPermissionLevel(permissionLevel)) {
            if (player.getMainHandStack().getItem() == Items.OBSIDIAN) {
                if (firstSignPos == null) {
                    firstSignPos = blockPos;
                    player.sendMessage(Text.literal(languageStrings.get("sign_a_selected")), true);
                    return ActionResult.SUCCESS;
                } else {
                    BlockPos secondSignPos = blockPos;

                    String firstPosKey = blockPosToString(firstSignPos);
                    String secondPosKey = blockPosToString(secondSignPos);

                    if (teleportLinks.containsKey(firstPosKey) || teleportLinks.containsKey(secondPosKey)) {
                        player.sendMessage(Text.literal(languageStrings.get("error_already_linked")), true);
                        firstSignPos = null;
                        return ActionResult.FAIL;
                    }

                    teleportLinks.put(firstPosKey, secondSignPos);
                    teleportLinks.put(secondPosKey, firstSignPos);
                    saveTeleportLinks();

                    player.sendMessage(Text.literal(languageStrings.get("teleport_link_set")), true);
                    firstSignPos = null;
                    return ActionResult.SUCCESS;
                }
            }
        }

        // Teletrasporto per i giocatori normali
        String posKey = blockPosToString(blockPos);
        if (teleportLinks.containsKey(posKey)) {
            BlockPos targetPos = teleportLinks.get(posKey);

            // Ottieni il testo del cartello di partenza prima del teletrasporto
            String signTextDeparture = getSignText(world, blockPos); 

            // Esegui il teletrasporto
            if (player instanceof ServerPlayerEntity) {
                ((ServerPlayerEntity) player).teleport((ServerWorld) world, targetPos.getX(), targetPos.getY(), targetPos.getZ(), player.getYaw(), player.getPitch());

                // Mostra il messaggio di teletrasporto con il testo del cartello di partenza
                player.sendMessage(Text.literal(languageStrings.get("teleported_to") + " " + signTextDeparture), true);
            }

            return ActionResult.SUCCESS;
        }

        return ActionResult.PASS;
    }

    private String getSignText(World world, BlockPos pos) {
        if (world.getBlockEntity(pos) instanceof SignBlockEntity sign) {
            SignText frontText = sign.getFrontText();
            return frontText.getMessage(0, false).getString();
        }
        return "Unknown location";
    }

    private void removeTeleportLink(BlockPos pos) {
        String posKey = blockPosToString(pos);
        if (teleportLinks.containsKey(posKey)) {
            String linkedPosKey = blockPosToString(teleportLinks.get(posKey));
            teleportLinks.remove(posKey);
            teleportLinks.remove(linkedPosKey);
            saveTeleportLinks();
        }
    }

    private void createConfigDirectory() {
        File configDir = new File("config/teleportmod");
        if (!configDir.exists()) {
            if (configDir.mkdirs()) {
                System.out.println("Config directory created: " + configDir.getAbsolutePath());
            } else {
                System.err.println("Failed to create config directory: " + configDir.getAbsolutePath());
            }
        }
    }

    private void loadTeleportLinks() {
        createConfigDirectory();

        if (TELEPORT_DATA_FILE.exists()) {
            try (FileReader reader = new FileReader(TELEPORT_DATA_FILE)) {
                Type type = new TypeToken<Map<String, BlockPos>>() {}.getType();
                teleportLinks = GSON.fromJson(reader, type);
                System.out.println("Teleport links loaded.");
            } catch (IOException e) {
                System.err.println("Error reading teleport_links.json: " + e.getMessage());
                e.printStackTrace();
            } catch (JsonSyntaxException e) {
                System.err.println("Invalid JSON format in teleport_links.json: " + e.getMessage());
                e.printStackTrace();
                teleportLinks = new HashMap<>();
                saveTeleportLinks();
            }
        } else {
            teleportLinks = new HashMap<>();
            saveTeleportLinks();
            System.out.println("New teleport links file created.");
        }
    }

    private void saveTeleportLinks() {
        createConfigDirectory();

        try (FileWriter writer = new FileWriter(TELEPORT_DATA_FILE)) {
            GSON.toJson(teleportLinks, writer);
            System.out.println("Teleport links saved.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadConfig() {
        createConfigDirectory();

        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                JsonObject config = GSON.fromJson(reader, JsonObject.class);
                if (config.has("permissionLevel")) {
                    permissionLevel = config.get("permissionLevel").getAsInt();
                }
                System.out.println("Configuration loaded: " + CONFIG_FILE.getAbsolutePath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            saveConfig();
            System.out.println("Default config file created: " + CONFIG_FILE.getAbsolutePath());
        }
    }

    private void saveConfig() {
        createConfigDirectory();

        JsonObject config = new JsonObject();
        config.addProperty("permissionLevel", permissionLevel);

        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(config, writer);
            System.out.println("Configuration saved: " + CONFIG_FILE.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadLanguage() {
        createConfigDirectory();

        if (LANGUAGE_FILE.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(LANGUAGE_FILE))) {
                languageStrings.clear();
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("=", 2);
                    if (parts.length == 2) {
                        languageStrings.put(parts[0].trim(), parts[1].trim());
                    }
                }
                System.out.println("Language file loaded: " + LANGUAGE_FILE.getAbsolutePath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            createDefaultLanguageFile();
            System.out.println("Default language file created: " + LANGUAGE_FILE.getAbsolutePath());
        }
    }

    private void createDefaultLanguageFile() {
        languageStrings.put("sign_a_selected", "Sign A selected!");
        languageStrings.put("teleport_link_set", "Teleport link set between A and B!");
        languageStrings.put("teleported_to", "Teleported to");
        languageStrings.put("no_permission_to_destroy", "You don't have permission to destroy this sign!");
        languageStrings.put("error_already_linked", "Error: One of the signs is already linked!");
        saveLanguage();
    }

    private void saveLanguage() {
        createConfigDirectory();

        try (FileWriter writer = new FileWriter(LANGUAGE_FILE)) {
            for (Map.Entry<String, String> entry : languageStrings.entrySet()) {
                writer.write(entry.getKey() + "=" + entry.getValue() + "\n");
            }
            System.out.println("Language file saved: " + LANGUAGE_FILE.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String blockPosToString(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public static class BlockPosSerializer implements JsonSerializer<BlockPos>, JsonDeserializer<BlockPos> {
        @Override
        public JsonElement serialize(BlockPos src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject obj = new JsonObject();
            obj.addProperty("x", src.getX());
            obj.addProperty("y", src.getY());
            obj.addProperty("z", src.getZ());
            return obj;
        }

        @Override
        public BlockPos deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            JsonObject obj = json.getAsJsonObject();
            int x = obj.get("x").getAsInt();
            int y = obj.get("y").getAsInt();
            int z = obj.get("z").getAsInt();
            return new BlockPos(x, y, z);
        }
    }
}
