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

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.R;

/**
 * Renders a {@link GWatchJsonNode} tree as a flat list of rows, one per visible entry.
 *
 * <p>Collapsing a container drops its descendants from that list rather than hiding views,
 * so a deeply nested config costs no more to show than a flat one. Expanding and collapsing
 * is handled here; everything that changes the document is handed to the {@link Listener}.
 */
public class GWatchJsonTreeAdapter extends RecyclerView.Adapter<GWatchJsonTreeAdapter.NodeViewHolder> {
    private static final int MENU_EDIT = 1;
    private static final int MENU_ADD = 2;
    private static final int MENU_DELETE = 3;

    public interface Listener {
        void onNodeEdit(GWatchJsonNode node);

        void onNodeAdd(GWatchJsonNode parent);

        void onNodeDelete(GWatchJsonNode node);
    }

    /// A node together with the nesting level it is drawn at
    private static class Row {
        final GWatchJsonNode node;
        final int depth;

        Row(final GWatchJsonNode node, final int depth) {
            this.node = node;
            this.depth = depth;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final Listener listener;
    private final String rootLabel;
    private final int indentStep;

    private GWatchJsonNode root;

    public GWatchJsonTreeAdapter(final Context context, final String rootLabel, final Listener listener) {
        this.rootLabel = rootLabel;
        this.listener = listener;
        this.indentStep = context.getResources().getDimensionPixelSize(R.dimen.gwatch_json_indent);
    }

    public void setRoot(final GWatchJsonNode root) {
        this.root = root;
        refresh();
    }

    /** Rebuild the visible rows after the tree changed underneath us. */
    @SuppressLint("NotifyDataSetChanged")
    public void refresh() {
        rows.clear();
        if (root != null) {
            append(root, 0);
        }
        notifyDataSetChanged();
    }

    private void append(final GWatchJsonNode node, final int depth) {
        rows.add(new Row(node, depth));
        if (node.getType().isContainer() && node.isExpanded()) {
            for (final GWatchJsonNode child : node.getChildren()) {
                append(child, depth + 1);
            }
        }
    }

    @NonNull
    @Override
    public NodeViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
        final View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_gwatch_json_node, parent, false);
        return new NodeViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final NodeViewHolder holder, final int position) {
        final Row row = rows.get(position);
        final GWatchJsonNode node = row.node;
        final boolean container = node.getType().isContainer();

        holder.indent.getLayoutParams().width = row.depth * indentStep;
        holder.indent.requestLayout();

        holder.chevron.setText(node.isExpanded() ? "▾" : "▸");
        // Kept in the layout when there is nothing to expand, so keys stay aligned
        holder.chevron.setVisibility(container ? View.VISIBLE : View.INVISIBLE);

        holder.key.setText(describeKey(node));
        holder.value.setText(describeValue(node));

        holder.itemView.setOnClickListener(v -> {
            if (container) {
                node.setExpanded(!node.isExpanded());
                refresh();
            } else if (!node.isRoot()) {
                // A whole document that is a bare scalar has nothing to edit it within
                listener.onNodeEdit(node);
            }
        });
        holder.menu.setOnClickListener(v -> showMenu(v, node));
    }

    private String describeKey(final GWatchJsonNode node) {
        return node.isRoot() ? rootLabel : node.getDisplayName();
    }

    private String describeValue(final GWatchJsonNode node) {
        switch (node.getType()) {
            case OBJECT:
                return String.format(Locale.ROOT, "{%d}", node.getChildCount());
            case ARRAY:
                return String.format(Locale.ROOT, "[%d]", node.getChildCount());
            case STRING:
                // Quoted so trailing spaces and escapes are visible in the row
                return JSONObject.quote(node.getValue());
            case NULL:
                return "null";
            default:
                return node.getValue();
        }
    }

    private void showMenu(final View anchor, final GWatchJsonNode node) {
        final PopupMenu popup = new PopupMenu(anchor.getContext(), anchor);
        // The root has no name and no container to be removed from, so it is add-only
        if (!node.isRoot()) {
            popup.getMenu().add(0, MENU_EDIT, 0, R.string.gwatch_json_edit);
        }
        if (node.getType().isContainer()) {
            popup.getMenu().add(0, MENU_ADD, 1, node.getType() == GWatchJsonNode.Type.OBJECT
                    ? R.string.gwatch_json_add_field : R.string.gwatch_json_add_item);
        }
        if (!node.isRoot()) {
            popup.getMenu().add(0, MENU_DELETE, 2, R.string.delete);
        }
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_EDIT:
                    listener.onNodeEdit(node);
                    return true;
                case MENU_ADD:
                    listener.onNodeAdd(node);
                    return true;
                case MENU_DELETE:
                    listener.onNodeDelete(node);
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class NodeViewHolder extends RecyclerView.ViewHolder {
        final View indent;
        final TextView chevron;
        final TextView key;
        final TextView value;
        final ImageButton menu;

        NodeViewHolder(@NonNull final View itemView) {
            super(itemView);
            indent = itemView.findViewById(R.id.gwatch_json_indent);
            chevron = itemView.findViewById(R.id.gwatch_json_chevron);
            key = itemView.findViewById(R.id.gwatch_json_key);
            value = itemView.findViewById(R.id.gwatch_json_value);
            menu = itemView.findViewById(R.id.gwatch_json_menu);
        }
    }
}
