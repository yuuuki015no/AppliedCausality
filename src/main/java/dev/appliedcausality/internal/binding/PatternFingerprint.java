package dev.appliedcausality.internal.binding;

import appeng.api.stacks.AEItemKey;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.EndTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Versioned digest of an exact AE2 pattern definition.
 *
 * <p>The definition is an {@link AEItemKey}, so its serialized form contains
 * the encoded item ID, full item tag, and serialized Forge capabilities while
 * deliberately excluding the physical {@link ItemStack} count. Compound keys
 * are sorted recursively before hashing; list order and every numeric value
 * remain significant.</p>
 */
public final class PatternFingerprint {
    public static final int ENCODING_VERSION = 1;

    private static final int SHA_256_LENGTH = 32;
    private static final byte[] DOMAIN = "applied_causality:pattern_fingerprint"
            .getBytes(StandardCharsets.UTF_8);
    private static final String VERSION_TAG = "version";
    private static final String DIGEST_TAG = "digest";

    private final int version;
    private final byte[] digest;

    private PatternFingerprint(int version, byte[] digest) {
        if (version != ENCODING_VERSION) {
            throw new IllegalArgumentException("unsupported fingerprint version: " + version);
        }
        Objects.requireNonNull(digest, "digest");
        if (digest.length != SHA_256_LENGTH) {
            throw new IllegalArgumentException("SHA-256 digest must contain 32 bytes");
        }
        this.version = version;
        this.digest = digest.clone();
    }

    public static PatternFingerprint fromDefinition(ItemStack definition) {
        Objects.requireNonNull(definition, "definition");
        AEItemKey definitionKey = Objects.requireNonNull(
                AEItemKey.of(definition), "pattern definition must not be empty");
        return fromDefinition(definitionKey);
    }

    public static PatternFingerprint fromDefinition(AEItemKey definition) {
        Objects.requireNonNull(definition, "definition");
        return new PatternFingerprint(ENCODING_VERSION, hashCanonical(definition.toTag()));
    }

    public int version() {
        return version;
    }

    public byte[] digest() {
        return digest.clone();
    }

    public CompoundTag save() {
        CompoundTag data = new CompoundTag();
        data.putInt(VERSION_TAG, version);
        data.putByteArray(DIGEST_TAG, digest);
        return data;
    }

    public static Optional<PatternFingerprint> load(CompoundTag data) {
        Objects.requireNonNull(data, "data");
        if (!data.contains(VERSION_TAG, Tag.TAG_INT)
                || !data.contains(DIGEST_TAG, Tag.TAG_BYTE_ARRAY)) {
            return Optional.empty();
        }
        int version = data.getInt(VERSION_TAG);
        byte[] digest = data.getByteArray(DIGEST_TAG);
        if (version != ENCODING_VERSION || digest.length != SHA_256_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(new PatternFingerprint(version, digest));
    }

    private static byte[] hashCanonical(CompoundTag definition) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.writeInt(DOMAIN.length);
                output.write(DOMAIN);
                output.writeInt(ENCODING_VERSION);
                writeTag(output, definition);
            }
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to encode pattern fingerprint", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("Java 17 must provide SHA-256", exception);
        }
    }

    private static void writeTag(DataOutputStream output, Tag tag) throws IOException {
        output.writeByte(tag.getId());
        if (tag instanceof EndTag) {
            return;
        }
        if (tag instanceof ByteTag value) {
            output.writeByte(value.getAsByte());
        } else if (tag instanceof ShortTag value) {
            output.writeShort(value.getAsShort());
        } else if (tag instanceof IntTag value) {
            output.writeInt(value.getAsInt());
        } else if (tag instanceof LongTag value) {
            output.writeLong(value.getAsLong());
        } else if (tag instanceof FloatTag value) {
            output.writeInt(Float.floatToRawIntBits(value.getAsFloat()));
        } else if (tag instanceof DoubleTag value) {
            output.writeLong(Double.doubleToRawLongBits(value.getAsDouble()));
        } else if (tag instanceof ByteArrayTag value) {
            byte[] array = value.getAsByteArray();
            output.writeInt(array.length);
            output.write(array);
        } else if (tag instanceof StringTag value) {
            writeString(output, value.getAsString());
        } else if (tag instanceof ListTag value) {
            output.writeInt(value.size());
            for (Tag element : value) {
                writeTag(output, element);
            }
        } else if (tag instanceof CompoundTag value) {
            var keys = value.getAllKeys().stream().sorted().toList();
            output.writeInt(keys.size());
            for (String key : keys) {
                writeString(output, key);
                writeTag(output, Objects.requireNonNull(value.get(key), "compound value"));
            }
        } else if (tag instanceof IntArrayTag value) {
            int[] array = value.getAsIntArray();
            output.writeInt(array.length);
            for (int element : array) {
                output.writeInt(element);
            }
        } else if (tag instanceof LongArrayTag value) {
            long[] array = value.getAsLongArray();
            output.writeInt(array.length);
            for (long element : array) {
                output.writeLong(element);
            }
        } else {
            throw new IllegalArgumentException("Unsupported NBT tag type: " + tag.getClass().getName());
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        // Java String equality is defined over UTF-16 code units. Encoding each
        // unit directly preserves isolated surrogates instead of replacing them
        // as String#getBytes(UTF_8) would.
        output.writeInt(value.length());
        for (int index = 0; index < value.length(); index++) {
            output.writeChar(value.charAt(index));
        }
    }

    @Override
    public boolean equals(Object object) {
        return this == object
                || object instanceof PatternFingerprint other
                && version == other.version
                && Arrays.equals(digest, other.digest);
    }

    @Override
    public int hashCode() {
        return 31 * version + Arrays.hashCode(digest);
    }

    @Override
    public String toString() {
        return "v" + version + ":" + HexFormat.of().formatHex(digest);
    }
}
