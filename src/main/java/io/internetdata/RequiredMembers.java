package io.internetdata;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.LogicalType;

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
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Refuses an answer without a member it must carry, or holding one the spec does not allow.
 *
 * <p>The generator starts every list member at an empty list and every other member at null, so
 * {@code {}} decoded as an empty catalog or no downloads (2.4.4, measured 2026-10-13), and an entry
 * without its members as a model of nulls (2.4.5, measured 2026-10-14); once decoded, the absence
 * could not be seen. Each member is checked on the tree before the generated model is built, so a
 * body without one, or with one null or holding a null, fails to decode, which {@link Wire} makes a
 * retried {@link ErrorKind#SERVER_ERROR} carrying the status. Jackson's own coercions are turned off for the
 * same reason: it read {@code "3"} as the number 3 and {@code 3} as the string "3" (2.5.0, measured
 * 2026-10-18).
 *
 * <p>Through 2.6.1 an optional list or map holding a null, a sample row that is no object, a
 * checksum answer without its {@code id} or {@code format}, and a {@code license_type} outside its
 * enum, which the generated nullable enum reads as null, all came back as answers (measured
 * 2026-10-26).
 */
final class RequiredMembers extends BeanDeserializerModifier {
    /**
     * By the generated model that carries them: each schema's {@code required} list less the members
     * it marks nullable, which a test holds to the models' own {@code @Nonnull} fields.
     */
    static final Map<Class<?>, List<String>> MEMBERS = Map.of(
            DatabaseList.class, List.of("databases"),
            DownloadList.class, List.of("downloads"),
            DatabaseChecksumsResponse.class, List.of("id", "format", "checksums"),
            Database.class, List.of("base", "name", "summary", "standing", "open", "versions"),
            DatabaseVersion.class, List.of("id", "version", "summary", "formats"),
            DatabaseMetadata.class, List.of("id", "updated", "entries", "schema", "size"),
            DatabaseMetadataColumn.class, List.of("name", "type"),
            DatabaseChecksums.class, List.of("md5", "sha1", "sha256", "sha512"),
            Download.class, List.of("dataset_id", "format", "outcome", "sample", "open", "created"));

    /** The members whose entries the spec types as objects, by model, which no generated type checks. */
    static final Map<Class<?>, String> OBJECT_ROWS = Map.of(DatabaseMetadata.class, "sample");

    /**
     * The mapper every answer is read with: no scalar read from another JSON type, dates read as RFC
     * 3339 alone, and this module.
     */
    static ObjectMapper configure(ObjectMapper mapper) {
        for (LogicalType type : List.of(LogicalType.Integer, LogicalType.Float, LogicalType.Boolean)) {
            mapper.coercionConfigFor(type).setCoercion(CoercionInputShape.String, CoercionAction.Fail);
        }
        mapper.coercionConfigFor(LogicalType.Integer).setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
        mapper.coercionConfigFor(LogicalType.Boolean).setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
        for (CoercionInputShape shape : List.of(CoercionInputShape.Integer, CoercionInputShape.Float,
                CoercionInputShape.Boolean)) {
            mapper.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
        }
        SimpleModule module = new SimpleModule("internetdata-required-members");
        module.setDeserializerModifier(new RequiredMembers());
        return mapper.registerModule(Rfc3339.module()).registerModule(module);
    }

    @Override
    public JsonDeserializer<?> modifyDeserializer(DeserializationConfig config, BeanDescription description,
            JsonDeserializer<?> deserializer) {
        Class<?> model = description.getBeanClass();
        if (!model.getPackageName().equals(Database.class.getPackageName())) {
            return deserializer;
        }
        // A map's values are checked like a list's entries, and an enum's value against its
        // constants; a model's members are its own deserializer's to check.
        Set<String> lists = new HashSet<>();
        Set<String> maps = new HashSet<>();
        Map<String, Set<String>> enums = new HashMap<>();
        for (BeanPropertyDefinition property : description.findProperties()) {
            Class<?> type = property.getRawPrimaryType();
            if (Map.class.isAssignableFrom(type)) {
                maps.add(property.getName());
            } else if (Collection.class.isAssignableFrom(type)) {
                lists.add(property.getName());
            } else if (type.isEnum()) {
                enums.put(property.getName(), Arrays.stream(type.getEnumConstants())
                        .map(String::valueOf).collect(Collectors.toUnmodifiableSet()));
            }
        }
        return new Requiring(deserializer, MEMBERS.getOrDefault(model, List.of()), Set.copyOf(lists),
                Set.copyOf(maps), Map.copyOf(enums), OBJECT_ROWS.get(model));
    }

    private static final class Requiring extends DelegatingDeserializer {
        private final List<String> members;
        private final Set<String> lists;
        private final Set<String> maps;
        private final Map<String, Set<String>> enums;
        private final String objectRows;

        Requiring(JsonDeserializer<?> delegate, List<String> members, Set<String> lists, Set<String> maps,
                Map<String, Set<String>> enums, String objectRows) {
            super(delegate);
            this.members = members;
            this.lists = lists;
            this.maps = maps;
            this.enums = enums;
            this.objectRows = objectRows;
        }

        @Override
        protected JsonDeserializer<?> newDelegatingInstance(JsonDeserializer<?> delegate) {
            return new Requiring(delegate, members, lists, maps, enums, objectRows);
        }

        @Override
        public Object deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode tree = context.readTree(parser);
            if (!tree.isObject()) {
                return context.reportInputMismatch(this, "the answer is not an object");
            }
            for (String member : members) {
                JsonNode value = tree.get(member);
                if (value == null || value.isNull()) {
                    return context.reportInputMismatch(this, "the answer has no %s", member);
                }
            }
            for (Map.Entry<String, JsonNode> member : tree.properties()) {
                String name = member.getKey();
                JsonNode value = member.getValue();
                if ((lists.contains(name) || maps.contains(name)) && holdsNull(value, maps.contains(name))) {
                    return context.reportInputMismatch(this, "the answer's %s holds a null", name);
                }
                Set<String> constants = enums.get(name);
                if (constants != null && !value.isNull() && !(value.isTextual() && constants.contains(value.asText()))) {
                    return context.reportInputMismatch(this, "the answer's %s is not one of %s", name, constants);
                }
                if (name.equals(objectRows) && !rowsAreObjects(value)) {
                    return context.reportInputMismatch(this, "the answer's %s holds a row that is not an object", name);
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

        // A map of lists, as the sample's rows keyed by format; any other shape is the generated
        // type's to refuse.
        private static boolean rowsAreObjects(JsonNode value) {
            for (JsonNode rows : value) {
                for (JsonNode row : rows) {
                    if (!row.isObject()) {
                        return false;
                    }
                }
            }
            return true;
        }
    }
}
