package io.internetdata;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;

import io.internetdata.model.DatabaseChecksumsResponse;
import io.internetdata.model.DatabaseList;
import io.internetdata.model.DownloadList;

import java.io.IOException;
import java.util.Map;

/**
 * Refuses a database answer without the member its call returns.
 *
 * <p>The generator starts every list member at an empty list, so {@code {}} decoded as an empty
 * catalog or no downloads, and once decoded the absence could not be seen (2.4.4, measured
 * 2026-10-13). The member is checked on the tree before the generated model is built, so a body
 * without it fails to decode, which {@link Wire} makes a retried {@link ErrorKind#SERVER_ERROR}
 * carrying the status.
 */
final class RequiredMembers extends BeanDeserializerModifier {
    private static final Map<Class<?>, String> MEMBERS = Map.of(
            DatabaseList.class, "databases",
            DownloadList.class, "downloads",
            DatabaseChecksumsResponse.class, "checksums");

    static SimpleModule module() {
        SimpleModule module = new SimpleModule("internetdata-required-members");
        module.setDeserializerModifier(new RequiredMembers());
        return module;
    }

    @Override
    public JsonDeserializer<?> modifyDeserializer(DeserializationConfig config, BeanDescription description,
            JsonDeserializer<?> deserializer) {
        String member = MEMBERS.get(description.getBeanClass());
        return member == null ? deserializer : new Requiring(deserializer, member);
    }

    private static final class Requiring extends DelegatingDeserializer {
        private final String member;

        Requiring(JsonDeserializer<?> delegate, String member) {
            super(delegate);
            this.member = member;
        }

        @Override
        protected JsonDeserializer<?> newDelegatingInstance(JsonDeserializer<?> delegate) {
            return new Requiring(delegate, member);
        }

        @Override
        public Object deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode tree = context.readTree(parser);
            if (!tree.isObject() || !tree.hasNonNull(member)) {
                return context.reportInputMismatch(this, "the answer has no %s", member);
            }
            try (JsonParser members = tree.traverse(parser.getCodec())) {
                members.nextToken();
                return _delegatee.deserialize(members, context);
            }
        }
    }
}
