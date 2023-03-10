package com.simibubi.create.content.schematics.client.tools;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.render.SuperRenderTypeBuffer;

import net.minecraftforge.client.gui.ForgeIngameGui;

public interface ISchematicTool {

	public void init();
	public void updateSelection();
	
	public boolean handleRightClick();
	public boolean handleMouseWheel(double delta);
	
	public void renderTool(PoseStack ms, SuperRenderTypeBuffer buffer);
	public void renderOverlay(ForgeIngameGui gui, PoseStack poseStack, float partialTicks, int width, int height);
	public void renderOnSchematic(PoseStack ms, SuperRenderTypeBuffer buffer);
	
}
