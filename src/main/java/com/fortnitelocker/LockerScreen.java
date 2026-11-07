package com.fortnitelocker;

import com.fortnitelocker.LockerState.Rarity;
import com.fortnitelocker.LockerState.Skin;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class LockerScreen extends Screen {
    private static final int GAP = 5, WHITE = 0xFFFFFFFF, TAB_Y = 38, TAB_H = 14;
    private static final String[] SORTS = {"Rarity", "Name", "Newest"};

    private final List<Skin> view = new ArrayList<>();
    private String query = "";
    private int tabFilter = -1;       // -1 = all, otherwise rarity ordinal
    private int sortMode = 0;
    private float scrollPos = 0, scrollTarget = 0;
    private boolean draggingBar = false;
    private String selectedId = null;
    private String status = "";
    private long statusUntil = 0;

    public LockerScreen() { super(Text.literal("Locker")); }

    // ---------- layout ----------
    private int leftW() { return Math.max(140, Math.min(240, width * 34 / 100)); }
    private int gridX() { return leftW() + 14; }
    private int gridRight() { return width - 22; }
    private int gridTop() { return 84; }
    private int gridBottom() { return height - 40; }
    private int cols() { return Math.max(3, Math.min(8, (gridRight() - gridX()) / 54)); }
    private int tile() {
        int c = cols();
        return Math.max(20, (gridRight() - gridX() - GAP * (c - 1)) / c);
    }
    private int contentH() {
        int rows = (int) Math.ceil(view.size() / (double) cols());
        return rows == 0 ? 0 : rows * (tile() + GAP) - GAP;
    }
    private int viewH() { return Math.max(1, gridBottom() - gridTop()); }
    private float maxScroll() { return Math.max(0, contentH() - viewH()); }

    // ---------- setup ----------
    @Override
    protected void init() {
        int gx = gridX(), gw = gridRight() - gx;
        TextFieldWidget search = new TextFieldWidget(textRenderer, gx, 58, gw - 72, 18, Text.literal("Search"));
        search.setPlaceholder(Text.literal("Search Outfits"));
        search.setMaxLength(32);
        search.setText(query);
        search.setChangedListener(s -> { query = s; refresh(); });
        addDrawableChild(search);

        addDrawableChild(ButtonWidget.builder(Text.literal("Sort: " + SORTS[sortMode]), b -> {
            sortMode = (sortMode + 1) % SORTS.length;
            b.setMessage(Text.literal("Sort: " + SORTS[sortMode]));
            refresh();
        }).dimensions(gx + gw - 68, 57, 68, 20).build());

        int y = height - 26, x = 10;
        addDrawableChild(ButtonWidget.builder(Text.literal("Unequip"), b -> LockerState.unequip())
            .dimensions(x, y, 58, 20).build());
        x += 62;
        addDrawableChild(ButtonWidget.builder(Text.literal("Edit"), b -> openEdit(selectedOrEquipped()))
            .dimensions(x, y, 40, 20).build());
        x += 44;
        addDrawableChild(ButtonWidget.builder(Text.literal("Skins Folder"), b -> {
            try { java.nio.file.Files.createDirectories(LockerState.skinsDir()); } catch (Exception ignored) {}
            Util.getOperatingSystem().open(LockerState.skinsDir().toFile());
        }).dimensions(x, y, 84, 20).build());
        x += 88;
        addDrawableChild(ButtonWidget.builder(Text.literal("Reload"), b -> { LockerState.reload(); refresh(); reportSkipped(); })
            .dimensions(x, y, 52, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> close())
            .dimensions(width - 64, y, 54, 20).build());
        refresh();
    }

    void refresh() {
        view.clear();
        String q = query.trim().toLowerCase();
        for (Skin s : LockerState.SKINS) {
            if (tabFilter >= 0 && s.rarity().ordinal() != tabFilter) continue;
            if (!q.isEmpty() && !s.name().toLowerCase().contains(q)) continue;
            view.add(s);
        }
        Comparator<Skin> byName = Comparator.comparing(s -> s.name().toLowerCase());
        switch (sortMode) {
            case 0 -> view.sort(Comparator.<Skin>comparingInt(s -> -s.rarity().ordinal()).thenComparing(byName));
            case 1 -> view.sort(byName);
            default -> view.sort(Comparator.<Skin>comparingLong(s -> -s.added()).thenComparing(byName));
        }
        scrollTarget = Math.max(0, Math.min(scrollTarget, maxScroll()));
        scrollPos = Math.max(0, Math.min(scrollPos, maxScroll()));
    }

    private void reportSkipped() {
        if (LockerState.skipped > 0) setStatus("Skipped " + LockerState.skipped + " file(s): skins must be 64x64 PNGs");
    }
    private void setStatus(String s) { status = s; statusUntil = System.currentTimeMillis() + 5000; }

    private Skin selectedOrEquipped() {
        Skin s = LockerState.byId(selectedId);
        return s != null ? s : LockerState.byId(LockerState.getEquippedId());
    }
    private void openEdit(Skin s) {
        if (s == null) { setStatus("Click a skin first, then press Edit (or right-click a skin)."); return; }
        client.setScreen(new EditSkinScreen(this, s));
    }

    // ---------- drag & drop upload ----------
    @Override
    public void onFilesDropped(List<Path> paths) {
        String firstId = null;
        for (Path p : paths) {
            if (!p.toString().toLowerCase().endsWith(".png")) continue;
            try {
                String id = LockerState.addFile(p);
                if (firstId == null) firstId = id;
            } catch (Exception e) {
                FortniteLockerClient.LOGGER.warn("Could not add {}: {}", p, e.getMessage());
            }
        }
        LockerState.reload();
        refresh();
        Skin added = LockerState.byId(firstId);
        if (added != null) {
            selectedId = added.id();
            client.setScreen(new EditSkinScreen(this, added));
        } else if (firstId != null) {
            reportSkipped();
        } else {
            setStatus("Drop .png skin files (64x64) onto this window.");
        }
    }

    // ---------- rendering ----------
    private void drawScaled(DrawContext ctx, String s, int x, int y, float sc, int color) {
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate((float) x, (float) y);
        m.scale(sc, sc);
        ctx.drawText(textRenderer, Text.literal(s), 0, 0, color, true);
        m.popMatrix();
    }

    private int[] tabBounds(int i) {
        int gx = gridX(), w0 = textRenderer.getWidth("OUTFIT") + 16;
        if (i == 0) return new int[]{gx, w0};
        return new int[]{gx + w0 + 6 + (i - 1) * 20, 17};
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // animate scroll
        float diff = scrollTarget - scrollPos;
        scrollPos = Math.abs(diff) < 0.5f ? scrollTarget : scrollPos + diff * 0.3f;

        ctx.fillGradient(0, 0, width, height, 0xFF2F86F0, 0xFF0B3A9C);
        ctx.fillGradient(0, 0, leftW() + 6, height, 0x00000000, 0x77000000);

        // ----- left: character preview -----
        if (client != null && client.player != null) {
            int y1 = 20, y2 = height - 92;
            int size = Math.max(30, (int) ((y2 - y1) * 0.42f));
            InventoryScreen.drawEntity(ctx, 10, y1, leftW() - 4, y2, size, 0.0625F,
                (float) mouseX, (float) mouseY, client.player);
        }
        Skin eq = LockerState.byId(LockerState.getEquippedId());
        drawScaled(ctx, "§l" + (eq == null ? "Default Skin" : eq.name()), 14, height - 82, 1.4f, WHITE);
        if (eq != null) ctx.drawText(textRenderer, Text.literal(eq.rarity().label() + " Outfit"),
            14, height - 66, 0xFF000000 | eq.rarity().color, true);
        else ctx.drawText(textRenderer, Text.literal("Your normal Minecraft skin"), 14, height - 66, 0xFFCCE0FF, true);
        int hy = height - 52;
        for (OrderedText line : textRenderer.wrapLines(
                Text.literal("Drag & drop a .png onto this window to add a skin. Right-click a skin to edit it."),
                leftW() - 20)) {
            ctx.drawText(textRenderer, line, 14, hy, 0xFFAFC8F5, false);
            hy += 10;
        }

        // ----- right: header, tabs, grid -----
        int gx = gridX();
        drawScaled(ctx, "§l§oCHARACTER", gx, 8, 2.2f, WHITE);

        String tip = null;
        for (int i = 0; i < 7; i++) {
            int[] b = tabBounds(i);
            boolean sel = i == 0 ? tabFilter == -1 : tabFilter == i - 1;
            if (i == 0) {
                ctx.fill(b[0], TAB_Y, b[0] + b[1], TAB_Y + TAB_H, sel ? 0x88000000 : 0x44000000);
                ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("OUTFIT"), b[0] + b[1] / 2, TAB_Y + 3, WHITE);
            } else {
                Rarity r = Rarity.values()[i - 1];
                ctx.fill(b[0], TAB_Y, b[0] + b[1], TAB_Y + TAB_H, 0xFF000000 | r.color);
                if (!sel) ctx.fill(b[0], TAB_Y, b[0] + b[1], TAB_Y + TAB_H, 0x77000000);
                if (mouseX >= b[0] && mouseX < b[0] + b[1] && mouseY >= TAB_Y && mouseY < TAB_Y + TAB_H) tip = r.label();
            }
            if (sel) ctx.fill(b[0], TAB_Y + TAB_H, b[0] + b[1], TAB_Y + TAB_H + 2, WHITE);
        }
        ctx.drawText(textRenderer, Text.literal(view.size() + " outfits"),
            gridRight() - textRenderer.getWidth(view.size() + " outfits"), TAB_Y + 3, 0xFFCCE0FF, true);

        int top = gridTop(), bot = gridBottom(), t = tile(), c = cols();
        Skin hovered = skinAt(mouseX, mouseY);
        ctx.enableScissor(gx - 3, top, gridRight() + 3, bot);
        int firstRow = Math.max(0, (int) scrollPos / (t + GAP));
        for (int idx = firstRow * c; idx < view.size(); idx++) {
            int y = top + (idx / c) * (t + GAP) - Math.round(scrollPos);
            if (y >= bot) break;
            if (y + t < top) continue;
            int x = gx + (idx % c) * (t + GAP);
            Skin s = view.get(idx);
            drawTile(ctx, s, x, y, t, s == hovered);
        }
        ctx.disableScissor();

        // scrollbar
        if (maxScroll() > 0) {
            int bx = width - 16;
            ctx.fill(bx, top, bx + 6, bot, 0x55000000);
            int knobH = Math.max(20, (int) ((float) viewH() * viewH() / contentH()));
            int knobY = top + (int) (scrollPos / maxScroll() * (viewH() - knobH));
            ctx.fill(bx, knobY, bx + 6, knobY + knobH, draggingBar ? 0xFFFFFFFF : 0xCCFFFFFF);
        }

        if (System.currentTimeMillis() < statusUntil)
            ctx.drawText(textRenderer, Text.literal("§e" + status), gx, height - 36, WHITE, true);

        super.render(ctx, mouseX, mouseY, delta);

        if (hovered != null) tip = hovered.name() + "  (" + hovered.rarity().label() + ")";
        if (tip != null) {
            int w = textRenderer.getWidth(tip) + 8;
            int tx = Math.min(mouseX + 8, width - w - 4), ty = Math.max(2, mouseY - 18);
            ctx.fill(tx, ty, tx + w, ty + 14, 0xEE100020);
            ctx.drawText(textRenderer, Text.literal(tip), tx + 4, ty + 3, WHITE, true);
        }
    }

    private static int lighten(int c) {
        int r = Math.min(255, ((c >> 16) & 255) + 55), g = Math.min(255, ((c >> 8) & 255) + 55), b = Math.min(255, (c & 255) + 55);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
    private static int darken(int c) {
        int r = (int) (((c >> 16) & 255) * 0.5f), g = (int) (((c >> 8) & 255) * 0.5f), b = (int) ((c & 255) * 0.5f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void drawTile(DrawContext ctx, Skin s, int x, int y, int t, boolean over) {
        int c = 0xFF000000 | s.rarity().color;
        ctx.fillGradient(x, y, x + t, y + t, lighten(c), darken(c));
        float u = (t - 18) / 32f;
        int fx = x + (t - Math.round(16 * u)) / 2;
        drawFigure(ctx, s.texture(), fx, y + 4, u);

        ctx.fill(x, y + t - 12, x + t, y + t, 0xAA000000);
        String n = s.name();
        if (textRenderer.getWidth(n) > t - 4) n = textRenderer.trimToWidth(n, t - 10) + "..";
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(n), x + t / 2, y + t - 10, WHITE);

        boolean equipped = s.id().equals(LockerState.getEquippedId());
        boolean selected = s.id().equals(selectedId);
        int bc = equipped || selected ? 0xFFFFFFFF : over ? 0xFFFFE066 : 0;
        if (bc != 0) {
            int th = equipped ? 2 : 1;
            ctx.fill(x, y, x + t, y + th, bc);
            ctx.fill(x, y + t - th, x + t, y + t, bc);
            ctx.fill(x, y, x + th, y + t, bc);
            ctx.fill(x + t - th, y, x + t, y + t, bc);
        }
        if (equipped) { // green check badge
            ctx.fill(x + t - 11, y + 2, x + t - 2, y + 11, 0xFF2ECC40);
            int[][] chk = {{2, 4}, {3, 5}, {4, 6}, {5, 5}, {6, 4}, {7, 3}};
            for (int[] p : chk) ctx.fill(x + t - 11 + p[0] - 1, y + 2 + p[1] - 1, x + t - 11 + p[0], y + 2 + p[1], WHITE);
        }
    }

    // Front-facing mini character built from the 64x64 skin texture.
    static void drawFigure(DrawContext ctx, Identifier tex, int fx, int fy, float u) {
        // base layer
        part(ctx, tex, fx, fy, u, 8, 8, 8, 8, 4, 0);
        part(ctx, tex, fx, fy, u, 20, 20, 8, 12, 4, 8);
        part(ctx, tex, fx, fy, u, 44, 20, 4, 12, 0, 8);
        part(ctx, tex, fx, fy, u, 36, 52, 4, 12, 12, 8);
        part(ctx, tex, fx, fy, u, 4, 20, 4, 12, 4, 20);
        part(ctx, tex, fx, fy, u, 20, 52, 4, 12, 8, 20);
        // overlay layer (hat, jacket, sleeves, pants)
        part(ctx, tex, fx, fy, u, 40, 8, 8, 8, 4, 0);
        part(ctx, tex, fx, fy, u, 20, 36, 8, 12, 4, 8);
        part(ctx, tex, fx, fy, u, 44, 36, 4, 12, 0, 8);
        part(ctx, tex, fx, fy, u, 52, 52, 4, 12, 12, 8);
        part(ctx, tex, fx, fy, u, 4, 36, 4, 12, 4, 20);
        part(ctx, tex, fx, fy, u, 4, 52, 4, 12, 8, 20);
    }

    private static void part(DrawContext ctx, Identifier tex, int fx, int fy, float u,
                             int sx, int sy, int sw, int sh, int ox, int oy) {
        int dx = fx + Math.round(ox * u), dy = fy + Math.round(oy * u);
        int dw = Math.round((ox + sw) * u) - Math.round(ox * u);
        int dh = Math.round((oy + sh) * u) - Math.round(oy * u);
        if (dw <= 0 || dh <= 0) return;
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, tex, dx, dy, (float) sx, (float) sy, dw, dh, sw, sh, 64, 64);
    }

    // ---------- input ----------
    private Skin skinAt(double mx, double my) {
        if (mx < gridX() || mx >= gridRight() || my < gridTop() || my >= gridBottom()) return null;
        int t = tile(), c = cols(), cell = t + GAP;
        double ry = my - gridTop() + scrollPos, rx = mx - gridX();
        if (ry % cell >= t || rx % cell >= t) return null;
        int col = (int) (rx / cell), row = (int) (ry / cell);
        if (col >= c) return null;
        int idx = row * c + col;
        return idx >= 0 && idx < view.size() ? view.get(idx) : null;
    }

    private void dragBarTo(double my) {
        int knobH = Math.max(20, (int) ((float) viewH() * viewH() / Math.max(1, contentH())));
        double frac = (my - gridTop() - knobH / 2.0) / Math.max(1, viewH() - knobH);
        scrollTarget = (float) Math.max(0, Math.min(maxScroll(), frac * maxScroll()));
        scrollPos = scrollTarget;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // tabs
        if (button == 0 && my >= TAB_Y && my < TAB_Y + TAB_H) {
            for (int i = 0; i < 7; i++) {
                int[] b = tabBounds(i);
                if (mx >= b[0] && mx < b[0] + b[1]) {
                    tabFilter = i - 1;
                    refresh();
                    return true;
                }
            }
        }
        // scrollbar
        if (button == 0 && maxScroll() > 0 && mx >= width - 18 && mx <= width - 8
                && my >= gridTop() && my < gridBottom()) {
            draggingBar = true;
            dragBarTo(my);
            return true;
        }
        // tiles
        Skin s = skinAt(mx, my);
        if (s != null) {
            selectedId = s.id();
            if (button == 0) {
                LockerState.equip(s);
                MinecraftClient.getInstance().getSoundManager().play(
                    PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
            } else if (button == 1) {
                openEdit(s);
            }
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (draggingBar) { dragBarTo(my); return true; }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        draggingBar = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        scrollTarget -= (float) vertical * (tile() + GAP) * 0.8f;
        scrollTarget = Math.max(0, Math.min(scrollTarget, maxScroll()));
        return true;
    }

    @Override
    public boolean shouldPause() { return false; }
}
