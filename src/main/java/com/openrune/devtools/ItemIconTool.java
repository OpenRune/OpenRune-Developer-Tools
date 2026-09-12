/*
 * Copyright (c) 2026, Mark7625
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.openrune.devtools;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NodeCache;
import net.runelite.api.SpritePixels;
import net.runelite.api.events.MenuOpened;
import net.runelite.client.callback.ClientThread;

/**
 * Interactive item icon editor: shift + right-click an item in any container widget
 * to get an "Edit icon" option, which opens a Swing window with sliders named after
 * the filestore ItemType fields: zoom2d, xan2d, yan2d, zan2d, xOffset2d, yOffset2d.
 *
 * <p>All six values are applied to the live {@link ItemComposition} and both sprite
 * caches are flushed, so in-game icons update immediately. xan2d/yan2d/zan2d go
 * through the RuneLite API setters; zoom2d/xOffset2d/yOffset2d have no API
 * accessors, so they are read and written via reflection on the client's public
 * (final) fields — this is a sideloaded dev tool for a controlled client, not a
 * hub plugin. If reflection fails (field layout change, JDK restriction) those
 * three sliders fall back to doing nothing and a warning is logged once.
 *
 * <p>"Copy values" puts all six values on the clipboard in filestore naming,
 * ready to paste into an item definition.
 */
@Slf4j
class ItemIconTool
{
	private static final int PREVIEW_SCALE = 5;
	private static final int DEFAULT_SPRITE_ZOOM = 512;
	// cache decode default when opcode 4 is absent; used if reflection fails
	private static final int DEFAULT_ZOOM2D = 2000;
	private static final int SPRITE_WIDTH = 36;
	private static final int SPRITE_HEIGHT = 32;

	private final Client client;
	private final ClientThread clientThread;

	// itemId -> original {xan2d, yan2d, zan2d, zoom2d, xOffset2d, yOffset2d}, restored on shutdown
	private final Map<Integer, int[]> originalValues = new ConcurrentHashMap<>();
	private final AtomicBoolean applyQueued = new AtomicBoolean();

	// reflection handles for the fields the API doesn't expose; resolved lazily
	// from the live ItemComposition class, null if resolution failed
	private Field zoomField;
	private Field xOffsetField;
	private Field yOffsetField;
	private boolean reflectionResolved;

	private volatile int itemId = -1;
	private volatile int pendingXan;
	private volatile int pendingYan;
	private volatile int pendingZan;
	private volatile int pendingZoom = DEFAULT_ZOOM2D;
	private volatile int pendingXOffset;
	private volatile int pendingYOffset;

	// Swing state, EDT only
	private JFrame frame;
	private JLabel titleLabel;
	private JLabel previewLabel;
	private JSlider zoomSlider;
	private JSlider xanSlider;
	private JSlider yanSlider;
	private JSlider zanSlider;
	private JSlider xOffsetSlider;
	private JSlider yOffsetSlider;
	private boolean suppressSliderEvents;

	ItemIconTool(Client client, ClientThread clientThread)
	{
		this.client = client;
		this.clientThread = clientThread;
	}

	void onMenuOpened(MenuOpened event)
	{
		if (!client.isKeyPressed(KeyCode.KC_SHIFT))
		{
			return;
		}
		for (MenuEntry entry : event.getMenuEntries())
		{
			int id = entry.getItemId();
			if (id > 0)
			{
				// entries render top-down from the highest index; index 1 sits
				// just above Cancel (index 0), i.e. at the bottom of the menu
				client.getMenu().createMenuEntry(1)
					.setOption("Edit icon")
					.setTarget(entry.getTarget())
					.setType(MenuAction.RUNELITE)
					.setItemId(id)
					.onClick(e -> select(e.getItemId()));
				return;
			}
		}
	}

	void shutDown()
	{
		clientThread.invoke(() ->
		{
			for (Map.Entry<Integer, int[]> entry : originalValues.entrySet())
			{
				ItemComposition def = client.getItemDefinition(entry.getKey());
				if (def != null)
				{
					int[] values = entry.getValue();
					def.setXan2d(values[0]);
					def.setYan2d(values[1]);
					def.setZan2d(values[2]);
					writeHiddenFields(def, values[3], values[4], values[5]);
				}
			}
			originalValues.clear();
			flushSpriteCaches();
		});
		SwingUtilities.invokeLater(() ->
		{
			if (frame != null)
			{
				frame.dispose();
				frame = null;
			}
		});
	}

	private void resolveFields(ItemComposition def)
	{
		if (reflectionResolved)
		{
			return;
		}
		reflectionResolved = true;
		try
		{
			Class<?> clazz = def.getClass();
			zoomField = clazz.getField("zoom2d");
			xOffsetField = clazz.getField("xOffset2d");
			yOffsetField = clazz.getField("yOffset2d");
			zoomField.setAccessible(true);
			xOffsetField.setAccessible(true);
			yOffsetField.setAccessible(true);
		}
		catch (ReflectiveOperationException | RuntimeException e)
		{
			zoomField = null;
			xOffsetField = null;
			yOffsetField = null;
			log.warn("Cannot access zoom2d/xOffset2d/yOffset2d via reflection; those sliders will be inert", e);
		}
	}

	private int[] readHiddenFields(ItemComposition def)
	{
		resolveFields(def);
		if (zoomField == null)
		{
			return new int[]{DEFAULT_ZOOM2D, 0, 0};
		}
		try
		{
			return new int[]{zoomField.getInt(def), xOffsetField.getInt(def), yOffsetField.getInt(def)};
		}
		catch (IllegalAccessException e)
		{
			log.warn("Failed reading item fields", e);
			return new int[]{DEFAULT_ZOOM2D, 0, 0};
		}
	}

	private void writeHiddenFields(ItemComposition def, int zoom, int xOffset, int yOffset)
	{
		resolveFields(def);
		if (zoomField == null)
		{
			return;
		}
		try
		{
			zoomField.setInt(def, zoom);
			xOffsetField.setInt(def, xOffset);
			yOffsetField.setInt(def, yOffset);
		}
		catch (IllegalAccessException e)
		{
			// final-field writes can be locked down by newer JDKs; disable and warn once
			zoomField = null;
			xOffsetField = null;
			yOffsetField = null;
			log.warn("Failed writing item fields; zoom/offset sliders disabled", e);
		}
	}

	// Menu onClick callbacks run on the client thread
	private void select(int id)
	{
		log.debug("Edit icon clicked for item {}", id);
		ItemComposition def = client.getItemDefinition(id);
		if (def == null)
		{
			log.debug("No item definition for {}", id);
			return;
		}
		itemId = id;
		int[] hidden = readHiddenFields(def);
		originalValues.putIfAbsent(id, new int[]{
			def.getXan2d(), def.getYan2d(), def.getZan2d(), hidden[0], hidden[1], hidden[2]});
		int xan = def.getXan2d();
		int yan = def.getYan2d();
		int zan = def.getZan2d();
		String name = def.getName();
		SwingUtilities.invokeLater(() -> showEditor(id, name, xan, yan, zan, hidden[0], hidden[1], hidden[2]));
	}

	private void showEditor(int id, String name, int xan, int yan, int zan, int zoom, int xOffset, int yOffset)
	{
		if (frame == null)
		{
			buildFrame();
		}
		titleLabel.setText(name + " (" + id + ")");
		suppressSliderEvents = true;
		zoomSlider.setValue(zoom);
		xanSlider.setValue(xan);
		yanSlider.setValue(yan);
		zanSlider.setValue(zan);
		xOffsetSlider.setValue(xOffset);
		yOffsetSlider.setValue(yOffset);
		suppressSliderEvents = false;
		pendingXan = xan;
		pendingYan = yan;
		pendingZan = zan;
		pendingZoom = zoom;
		pendingXOffset = xOffset;
		pendingYOffset = yOffset;
		scheduleApply();
		frame.setVisible(true);
		frame.toFront();
	}

	private void buildFrame()
	{
		frame = new JFrame("Item icon editor");
		frame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
		// the client window usually holds focus; without this the editor opens behind it
		frame.setAlwaysOnTop(true);

		JPanel content = new JPanel(new BorderLayout(8, 8));
		content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		titleLabel = new JLabel(" ", SwingConstants.CENTER);

		previewLabel = new JLabel("", SwingConstants.CENTER);
		previewLabel.setPreferredSize(new Dimension(SPRITE_WIDTH * PREVIEW_SCALE, SPRITE_HEIGHT * PREVIEW_SCALE));

		JPanel top = new JPanel(new BorderLayout(4, 4));
		top.add(titleLabel, BorderLayout.NORTH);
		top.add(previewLabel, BorderLayout.CENTER);

		// setValue below fires change listeners while later sliders are still null
		suppressSliderEvents = true;
		JPanel sliders = new JPanel(new GridBagLayout());
		zoomSlider = addSliderRow(sliders, 0, "zoom2d (zoom)", 100, 6000);
		zoomSlider.setValue(DEFAULT_ZOOM2D);
		xanSlider = addSliderRow(sliders, 1, "xan2d (pitch)", 0, 2047);
		yanSlider = addSliderRow(sliders, 2, "yan2d (yaw)", 0, 2047);
		zanSlider = addSliderRow(sliders, 3, "zan2d (roll)", 0, 2047);
		xOffsetSlider = addSliderRow(sliders, 4, "xOffset2d (x shift)", -100, 100);
		xOffsetSlider.setValue(0);
		yOffsetSlider = addSliderRow(sliders, 5, "yOffset2d (y shift)", -100, 100);
		yOffsetSlider.setValue(0);
		suppressSliderEvents = false;

		JButton copyButton = new JButton("Copy to clipboard");
		copyButton.addActionListener(e -> copyValues());
		JButton resetButton = new JButton("Reset");
		resetButton.addActionListener(e -> resetCurrent());
		JPanel buttons = new JPanel();
		buttons.add(copyButton);
		buttons.add(resetButton);

		content.add(top, BorderLayout.NORTH);
		content.add(sliders, BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);
		frame.setContentPane(content);
		frame.pack();
		frame.setLocationRelativeTo(null);
	}

	private JSlider addSliderRow(JPanel panel, int row, String name, int min, int max)
	{
		GridBagConstraints c = new GridBagConstraints();
		c.gridy = row;
		c.insets = new Insets(2, 2, 2, 2);
		c.anchor = GridBagConstraints.WEST;

		c.gridx = 0;
		JLabel nameLabel = new JLabel(name);
		nameLabel.setPreferredSize(new Dimension(140, nameLabel.getPreferredSize().height));
		panel.add(nameLabel, c);

		JSlider slider = new JSlider(min, max, min);
		slider.setPreferredSize(new Dimension(260, slider.getPreferredSize().height));
		c.gridx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		panel.add(slider, c);

		JLabel valueLabel = new JLabel(String.valueOf(min));
		valueLabel.setPreferredSize(new Dimension(40, valueLabel.getPreferredSize().height));
		c.gridx = 2;
		c.fill = GridBagConstraints.NONE;
		c.weightx = 0;
		panel.add(valueLabel, c);

		slider.addChangeListener(e ->
		{
			valueLabel.setText(String.valueOf(slider.getValue()));
			onSliderChange();
		});
		return slider;
	}

	private void onSliderChange()
	{
		if (suppressSliderEvents)
		{
			return;
		}
		pendingZoom = zoomSlider.getValue();
		pendingXan = xanSlider.getValue();
		pendingYan = yanSlider.getValue();
		pendingZan = zanSlider.getValue();
		pendingXOffset = xOffsetSlider.getValue();
		pendingYOffset = yOffsetSlider.getValue();
		scheduleApply();
	}

	// Client thread only. Item sprites cache into the item sprite cache; if3
	// widgets can also hold their own cached copy, so flush both.
	private void flushSpriteCaches()
	{
		client.getItemSpriteCache().reset();
		NodeCache widgetSpriteCache = client.getWidgetSpriteCache();
		if (widgetSpriteCache != null)
		{
			widgetSpriteCache.reset();
		}
	}

	private void scheduleApply()
	{
		if (applyQueued.compareAndSet(false, true))
		{
			clientThread.invoke(this::applyPending);
		}
	}

	private void applyPending()
	{
		applyQueued.set(false);
		int id = itemId;
		if (id < 0)
		{
			return;
		}
		ItemComposition def = client.getItemDefinition(id);
		if (def == null)
		{
			return;
		}
		def.setXan2d(pendingXan);
		def.setYan2d(pendingYan);
		def.setZan2d(pendingZan);
		writeHiddenFields(def, pendingZoom, pendingXOffset, pendingYOffset);
		// flush stale sprites so both the preview below and in-game icons re-render
		flushSpriteCaches();
		SpritePixels sprite = client.createItemSprite(
			id, 1, 1, SpritePixels.DEFAULT_SHADOW_COLOR, 0, false, DEFAULT_SPRITE_ZOOM);
		BufferedImage image = sprite != null ? sprite.toBufferedImage() : null;
		SwingUtilities.invokeLater(() -> updatePreview(image));
	}

	private void updatePreview(BufferedImage image)
	{
		if (previewLabel == null)
		{
			return;
		}
		if (image == null)
		{
			previewLabel.setIcon(null);
			previewLabel.setText("(model not loaded)");
			return;
		}
		Image scaled = image.getScaledInstance(
			image.getWidth() * PREVIEW_SCALE, image.getHeight() * PREVIEW_SCALE, Image.SCALE_REPLICATE);
		previewLabel.setText(null);
		previewLabel.setIcon(new ImageIcon(scaled));
	}

	private void copyValues()
	{
		String text =
			"zoom2d = " + pendingZoom + "\n"
			+ "xan2d = " + pendingXan + "\n"
			+ "yan2d = " + pendingYan + "\n"
			+ "zan2d = " + pendingZan + "\n"
			+ "xOffset2d = " + pendingXOffset + "\n"
			+ "yOffset2d = " + pendingYOffset;
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
	}

	private void resetCurrent()
	{
		int[] values = originalValues.get(itemId);
		if (values == null)
		{
			return;
		}
		// setValue fires the change listeners, which re-apply on the client thread
		xanSlider.setValue(values[0]);
		yanSlider.setValue(values[1]);
		zanSlider.setValue(values[2]);
		zoomSlider.setValue(values[3]);
		xOffsetSlider.setValue(values[4]);
		yOffsetSlider.setValue(values[5]);
	}
}
