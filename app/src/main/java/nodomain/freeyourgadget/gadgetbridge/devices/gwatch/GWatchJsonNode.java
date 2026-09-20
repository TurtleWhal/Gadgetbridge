/*  Copyright (C) 2026 Garrett Jordan

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.devices.gwatch;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One entry of a JSON document, as an editable tree.
 *
 * <p>This backs the config editor's tree mode. It exists because org.json's own types are
 * awkward to edit in place - there is no parent link to delete or rename through, and an
 * entry cannot be reordered or retyped without rebuilding its container. A node here knows
 * its parent, so every edit the UI offers is a local operation.
 *
 * <p>Scalars keep their literal as typed rather than as a parsed number, so writing a
 * document back out only reformats the parts that were actually edited.
 */
public class GWatchJsonNode {
    /// JSON number syntax, used to keep hand-typed numbers from producing invalid documents
    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?");

    private static final String INDENT = "  ";

    public enum Type {
        OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL;

        public boolean isContainer() {
            return this == OBJECT || this == ARRAY;
        }

        /** True if entries of this type carry a name of their own. */
        public boolean isNamedContainer() {
            return this == OBJECT;
        }
    }

    private GWatchJsonNode parent;
    /// Name within the containing object, or null for array elements and the root
    private String key;
    private Type type;
    /// Literal for strings, numbers and booleans; null for containers and null entries
    private String value;
    private final List<GWatchJsonNode> children = new ArrayList<>();
    private boolean expanded = true;

    private GWatchJsonNode(final String key, final Type type, final String value) {
        this.key = key;
        this.type = type;
        this.value = type.isContainer() || type == Type.NULL ? null : value;
    }

    public static GWatchJsonNode create(final String key, final Type type, final String value) {
        return new GWatchJsonNode(key, type, value);
    }

    /** True if the text is a number JSON can represent - notably not NaN or an infinity. */
    public static boolean isValidNumber(final String text) {
        return NUMBER.matcher(text).matches();
    }

    /**
     * Parse a whole JSON document.
     *
     * @throws JSONException if the text is not a single well-formed JSON value
     */
    public static GWatchJsonNode parse(final String json) throws JSONException {
        final JSONTokener tokener = new JSONTokener(json);
        final Object parsed = tokener.nextValue();
        // nextValue stops at the end of the first value, so anything left over means we
        // were handed something that is not one document
        if (tokener.nextClean() != 0) {
            throw new JSONException("Unexpected content after the end of the document");
        }
        return fromValue(null, parsed);
    }

    private static GWatchJsonNode fromValue(final String key, final Object value) throws JSONException {
        if (value instanceof JSONObject) {
            final JSONObject object = (JSONObject) value;
            final GWatchJsonNode node = new GWatchJsonNode(key, Type.OBJECT, null);
            // JSONObject is insertion-ordered on Android, so this keeps the file's order
            final Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                final String childKey = keys.next();
                node.attach(fromValue(childKey, object.get(childKey)));
            }
            return node;
        }
        if (value instanceof JSONArray) {
            final JSONArray array = (JSONArray) value;
            final GWatchJsonNode node = new GWatchJsonNode(key, Type.ARRAY, null);
            for (int i = 0; i < array.length(); i++) {
                node.attach(fromValue(null, array.get(i)));
            }
            return node;
        }
        if (value == null || JSONObject.NULL.equals(value)) {
            return new GWatchJsonNode(key, Type.NULL, null);
        }
        if (value instanceof Boolean) {
            return new GWatchJsonNode(key, Type.BOOLEAN, value.toString());
        }
        if (value instanceof Number) {
            return new GWatchJsonNode(key, Type.NUMBER, value.toString());
        }
        return new GWatchJsonNode(key, Type.STRING, value.toString());
    }

    /** The document as indented JSON, ready for the text editor or the watch. */
    public String toJson() {
        final StringBuilder builder = new StringBuilder();
        write(builder, 0);
        return builder.toString();
    }

    private void write(final StringBuilder builder, final int depth) {
        switch (type) {
            case OBJECT:
            case ARRAY:
                final boolean object = type == Type.OBJECT;
                if (children.isEmpty()) {
                    builder.append(object ? "{}" : "[]");
                    return;
                }
                builder.append(object ? '{' : '[').append('\n');
                for (int i = 0; i < children.size(); i++) {
                    final GWatchJsonNode child = children.get(i);
                    indent(builder, depth + 1);
                    if (object) {
                        builder.append(JSONObject.quote(child.key)).append(": ");
                    }
                    child.write(builder, depth + 1);
                    if (i < children.size() - 1) {
                        builder.append(',');
                    }
                    builder.append('\n');
                }
                indent(builder, depth);
                builder.append(object ? '}' : ']');
                break;
            case STRING:
                builder.append(JSONObject.quote(value));
                break;
            case NULL:
                builder.append("null");
                break;
            default:
                // Numbers and booleans are already stored as the literal to write
                builder.append(value);
                break;
        }
    }

    private static void indent(final StringBuilder builder, final int depth) {
        for (int i = 0; i < depth; i++) {
            builder.append(INDENT);
        }
    }

    private void attach(final GWatchJsonNode child) {
        child.parent = this;
        children.add(child);
    }

    /** Append an entry, opening this container so the new entry is visible. */
    public void addChild(final GWatchJsonNode child) {
        attach(child);
        expanded = true;
    }

    public void removeFromParent() {
        if (parent != null) {
            parent.children.remove(this);
            parent = null;
        }
    }

    /**
     * Retype this entry, dropping anything it held that the new type cannot carry.
     *
     * <p>Changing between two container types would leave array elements needing names, or
     * object members losing theirs, so any change of type empties the entry. Callers are
     * expected to warn first when {@link #getChildCount()} is not zero.
     */
    public void setType(final Type newType, final String newValue) {
        if (newType != type) {
            children.clear();
        }
        type = newType;
        value = newType.isContainer() || newType == Type.NULL ? null : newValue;
    }

    public GWatchJsonNode findChild(final String childKey) {
        for (final GWatchJsonNode child : children) {
            if (childKey.equals(child.key)) {
                return child;
            }
        }
        return null;
    }

    public GWatchJsonNode getParent() {
        return parent;
    }

    public boolean isRoot() {
        return parent == null;
    }

    public String getKey() {
        return key;
    }

    public void setKey(final String key) {
        this.key = key;
    }

    /** Name to show for this entry: its key, or its position when it lives in an array. */
    public String getDisplayName() {
        final int index = getIndexInParent();
        return index >= 0 ? "[" + index + "]" : key;
    }

    /** Position within the containing array, or -1 if this is not an array element. */
    public int getIndexInParent() {
        return parent != null && parent.type == Type.ARRAY ? parent.children.indexOf(this) : -1;
    }

    public Type getType() {
        return type;
    }

    public String getValue() {
        return value;
    }

    public List<GWatchJsonNode> getChildren() {
        return Collections.unmodifiableList(children);
    }

    public int getChildCount() {
        return children.size();
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(final boolean expanded) {
        this.expanded = expanded;
    }
}
