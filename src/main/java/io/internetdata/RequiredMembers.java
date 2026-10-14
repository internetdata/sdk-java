package io.internetdata;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.module.SimpleModule;

import io.internetdata.model.Database;
import io.internetdata.model.DatabaseChecksums;
import io.internetdata.model.DatabaseChecksumsResponse;
import io.internetdata.model.DatabaseList;
import io.internetdata.model.DatabaseMetadata;
import io.internetdata.model.DatabaseMetadataColumn;
import io.internetdata.model.DatabaseVersion;
import io.internetdata.model.Download;
import io.internetdata.model.DownloadList;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Refuses a database answer without a member it must carry.
 *
 * <p>The generator starts every list member at an empty list and every other member at null, so
 * {@code {}} decoded as an empty catalog or no downloads (2.4.4, measured 2026-10-13), and an entry
 * without its members as a model of nulls (2.4.5, measured 2026-10-14); once decoded, the absence
 * could not be seen. Each member is checked on the tree before the generated model is built, so a
 * body without one, or with one null or holding a null, fails to decode, which {@link Wire} makes a
 * retried {@link ErrorKind#SERVER_ERROR} carrying the status.
 */
final class RequiredMembers extends BeanDeserializerModifier {
    /**
     * By the generated model that carries them: each schema's {@code required} list less the members
     * it marks nullable, which a test holds to the models' own {@code @Nonnull} fields. An envelope
     * needs only the member its call returns.
     */
    static final Map<Class<?>, List<String>> MEMBERS = Map.of(
            DatabaseList.class, List.of("databases"),
            DownloadList.class, List.of("downloads"),
            DatabaseChecksumsResponse.class, List.of("checksums"),
            Database.class, List.of("base", "name", "summary", "standing", "open", "versions"),
            DatabaseVersion.class, List.of("id", "version", "summary", "formats"),
            DatabaseMetadata.class, List.of("id", "updated", "entries", "schema", "size"),
            DatabaseMetadataColumn.class, List.of("name", "type"),
            DatabaseChecksums.class, List.of("md5", "sha1", "sha256", "sha512"),
            Download.class, List.of("dataset_id", "format", "outcome", "sample", "open", "created"));

    static SimpleModule module() {
        SimpleModule module = new SimpleModule("internetdata-required-members");
        module.setDeserializerModifier(new RequiredMembers());
        return module;
    }

    @Override
    public JsonDeserializer<?> modifyDeserializer(DeserializationConfig config, BeanDescription description,
            JsonDeserializer<?> deserializer) {
        List<String> members = MEMBERS.get(description.getBeanClass());
        if (members == null) {
            return deserializer;
        }
        // A map's values are checked like a list's entries; a model's members are its own
        // deserializer's to check.
        Set<String> maps = new HashSet<>();
        for (BeanPropertyDefinition property : description.findProperties()) {
            if (members.contains(property.getName()) && Map.class.isAssignableFrom(property.getRawPrimaryType())) {
                maps.add(property.getName());
            }
        }
        return new Requiring(deserializer, members, Set.copyOf(maps));
    }

    private static final class Requiring extends DelegatingDeserializer {
        private final List<String> members;
        private final Set<String> maps;

        Requiring(JsonDeserializer<?> delegate, List<String> members, Set<String> maps) {
            super(delegate);
            this.members = members;
            this.maps = maps;
        }

        @Override
        protected JsonDeserializer<?> newDelegatingInstance(JsonDeserializer<?> delegate) {
            return new Requiring(delegate, members, maps);
        }

        @Override
        public Object deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode tree = context.readTree(parser);
            if (!tree.isObject()) {
                return context.reportInputMismatch(this, "the answer is not an object");
            }
            for (String member : members) {
                JsonNode value = tree.get(member);
                if (value == null || value.isNull() || holdsNull(value, maps.contains(member))) {
                    return context.reportInputMismatch(this, "the answer has no %s", member);
                }
            }
            try (JsonParser read = tree.traverse(parser.getCodec())) {
                read.nextToken();
                return _delegatee.deserialize(read, context);
            }
        }

        private static boolean holdsNull(JsonNode value, boolean map) {
            if (!value.isArray() && !(map && value.isObject())) {
                return false;
            }
            for (JsonNode entry : value) {
                if (entry.isNull() || holdsNull(entry, false)) {
                    return true;
                }
            }
            return false;
        }
    }
}
