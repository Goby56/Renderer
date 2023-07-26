package me.x150.renderer.objfile;

import com.mojang.blaze3d.systems.RenderSystem;
import de.javagl.obj.*;
import me.x150.renderer.util.BufferUtils;
import me.x150.renderer.util.RendererUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

/**
 * A wavefront obj file parser.
 * <h2>General Info</h2>
 * This implementation has been tested and optimized to work well with blender exported OBJs. OBJs exported from other sources may not work as well.<br>
 * When exporting a model in blender (for use with this library), make sure the following options are set:
 * <ul>
 *     <li>Forward axis: Either X, Y, -X or -Y</li>
 *     <li><b>Up axis: Y</b></li>
 *     <li>UV Coordinates: Yes</li>
 *     <li>Normals: Yes</li>
 *     <li><b>Triangulated mesh: Yes</b>*</li>
 * </ul>
 * <b>Highlighted options</b> are especially important.<br><br>
 * *: Non-triangulated meshes may not work, triangulation may fail.<br><br>
 * <h2>Parsing</h2>
 * This class uses {@link ObjReader} to read and parse .obj files. .mtl files are also handled by said library.
 * File access is managed by the {@link ResourceProvider} interface, which has the job of mapping a file name into a readable file.
 * <h2>Rendering</h2>
 * To render a loaded ObjFile, call {@link #draw(MatrixStack, Matrix4f, Vec3d, World)}.
 */
public class ObjFile implements Closeable {
	private final ResourceProvider provider;
	private final String name;
	final Map<Obj, VertexBuffer> buffers = new HashMap<>();
	final Map<String, Identifier> boundTextures = new HashMap<>();
	Map<String, Obj> materialNameObjMap;
	private List<Mtl> allMaterials;
	private Integer bakedLight = null;
	private boolean closed = false;

	/**
	 * Creates a new ObjFile
	 *
	 * @param name     Filename of the target .obj, resolved by the {@link ResourceProvider} {@code provider}
	 * @param provider The resource provider to use
	 * @throws IOException When reading the .obj fails
	 */
	public ObjFile(String name, ResourceProvider provider) throws IOException {
		this.name = name;
		this.provider = provider;
		read();
	}

	private static Vec3d transformVec3d(Vec3d in) {
		Camera camera = MinecraftClient.getInstance().gameRenderer.getCamera();
		Vec3d camPos = camera.getPos();
		return in.subtract(camPos);
	}

	private void read() throws IOException {
		try (InputStream reader = provider.open(name)) {
			Obj r = ObjUtils.convertToRenderable(ObjReader.read(reader));
			allMaterials = new ArrayList<>();
			for (String mtlFileName : r.getMtlFileNames()) {
				try (InputStream openReaderTo = provider.open(mtlFileName)) {
					List<Mtl> read = MtlReader.read(openReaderTo);
					allMaterials.addAll(read);
				}
			}
			materialNameObjMap = ObjSplitting.splitByMaterialGroups(r);

		}
	}

	private Identifier createTex0(String s) {
		try (InputStream reader = this.provider.open(s)) {
			Identifier identifier = RendererUtils.randomIdentifier();
			BufferedImage read1 = ImageIO.read(reader);
			RendererUtils.registerBufferedImageTexture(identifier, read1);
			return identifier;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private void bake(int light) {
		BufferBuilder b = Tessellator.getInstance().getBuffer();
		for (Map.Entry<String, Obj> stringObjEntry : materialNameObjMap.entrySet()) {
			String materialName = stringObjEntry.getKey();
			Obj objToDraw = stringObjEntry.getValue();
			Mtl material = allMaterials.stream().filter(f -> f.getName().equals(materialName)).findFirst().orElse(null);
			boolean hasTexture = material != null && material.getMapKd() != null;
			if (hasTexture) {
				String mapKd = material.getMapKd();
				boundTextures.computeIfAbsent(mapKd, this::createTex0);
			}
			VertexFormat vmf;
			if (material != null) {
				vmf = hasTexture ? VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL : VertexFormats.POSITION_COLOR_LIGHT;
			} else {
				vmf = VertexFormats.POSITION;
			}
			b.begin(VertexFormat.DrawMode.TRIANGLES, vmf);
			for (int i = 0; i < objToDraw.getNumFaces(); i++) {
				ObjFace face = objToDraw.getFace(i);
				boolean hasNormals = face.containsNormalIndices();
				boolean hasUV = face.containsTexCoordIndices();
				for (int i1 = 0; i1 < face.getNumVertices(); i1++) {
					FloatTuple xyz = objToDraw.getVertex(face.getVertexIndex(i1));
					// POSITION
					VertexConsumer vertex = b.vertex(xyz.getX(), xyz.getY(), xyz.getZ());
					// COLOR
					if (vmf == VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL || vmf == VertexFormats.POSITION_COLOR_LIGHT) {
						Objects.requireNonNull(material);
						FloatTuple kd = material.getKd();
						if (kd != null) {
							vertex.color(kd.getX(), kd.getY(), kd.getZ(), 1f);
						} else {
							vertex.color(1f, 1f, 1f, 1f);
						}
					}
					// TEXTURE
					if (vmf == VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL) {
						if (!hasUV) {
							throw new IllegalStateException(
									"Diffuse texture present, vertex doesn't have UV coordinates. File corrupted?");
						}
						if (!hasNormals) {
							throw new IllegalStateException(
									"Diffuse texture present, vertex doesn't have normal coordinates. File corrupted?");
						}
						FloatTuple uvs = objToDraw.getTexCoord(face.getTexCoordIndex(i1));
						vertex.texture(uvs.getX(), 1 - uvs.getY());
					}
					// LIGHT
					vertex.overlay(OverlayTexture.DEFAULT_UV);
					vertex.light(light);
					// NORMAL
					if (vmf == VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL) {
						FloatTuple normals = objToDraw.getNormal(face.getNormalIndex(i1));
						vertex.normal(normals.getX(), normals.getY(), normals.getZ());
					}
					vertex.next();
				}
			}
			BufferBuilder.BuiltBuffer end = b.end();
			buffers.put(objToDraw, BufferUtils.createVbo(end));
		}
		bakedLight = light;
	}

	/**
	 * Draws this ObjFile. Calls {@link #bake(int)} if necessary.
	 *
	 * @param stack      MatrixStack
	 * @param viewMatrix View matrix to apply to this ObjFile, independent of any other matrix.
	 * @param origin     Origin point to draw at
	 */
	public void draw(MatrixStack stack, Matrix4f viewMatrix, Vec3d origin, int light) {
		if (closed) {
			throw new IllegalStateException("Closed");
		}
			bake(light);
		Vec3d o = transformVec3d(origin);
		Matrix4f projectionMatrix = RenderSystem.getProjectionMatrix();
		Matrix4f m4f = new Matrix4f(stack.peek().getPositionMatrix());
		m4f.translate((float) o.x, (float) o.y, (float) o.z);
		m4f.mul(viewMatrix);

		RendererUtils.setupRender();
		RenderSystem.enableCull();
		MinecraftClient.getInstance().gameRenderer.getLightmapTextureManager().enable();
		for (Map.Entry<String, Obj> stringObjEntry : materialNameObjMap.entrySet()) {
			String materialName = stringObjEntry.getKey();
			Obj obj = stringObjEntry.getValue();
			Mtl material = allMaterials.stream().filter(f -> f.getName().equals(materialName)).findFirst().orElse(null);
			boolean hasTexture = material != null && material.getMapKd() != null;
			if (hasTexture) {
				String mapKd = material.getMapKd();
				Identifier identifier = boundTextures.get(mapKd);
				RenderSystem.setShaderTexture(0, identifier);
			}
			Supplier<ShaderProgram> shader;
			if (material != null) {
				shader = hasTexture ? GameRenderer::getRenderTypeEntityTranslucentProgram : GameRenderer::getPositionColorLightmapProgram;
			} else {
				shader = GameRenderer::getPositionProgram;
			}
			VertexBuffer vertexBuffer = buffers.get(obj);
			vertexBuffer.bind();
			vertexBuffer.draw(m4f, projectionMatrix, shader.get());
		}
		VertexBuffer.unbind();
		RendererUtils.endRender();
	}

	/**
	 * Clears all associated VertexBuffers, removes every linked texture and closes this ObjFile. All subsequent calls to any method will fail.
	 */
	@Override
	public void close() {
		for (VertexBuffer buffer : buffers.values()) {
			buffer.close();
		}

		for (Identifier value : boundTextures.values()) {
			MinecraftClient.getInstance().getTextureManager().destroyTexture(value);
		}
		boundTextures.clear();
		allMaterials.clear();
		closed = true;
	}

	/**
	 * A function, which maps a resource name found in an .obj file to an InputStream
	 */
	@FunctionalInterface
	public interface ResourceProvider {
		/**
		 * Appends the filename to read to a Path, then tries to load the resulting file.
		 *
		 * @param parent Parent path of all files
		 * @return New ResourceProvider
		 */
		static ResourceProvider ofPath(Path parent) {
			return name -> {
				Path resolve = parent.resolve(name);
				return Files.newInputStream(resolve);
			};
		}

		/**
		 * Opens {@code name} as InputStream
		 *
		 * @param name Filename to open
		 * @return The opened InputStream. Closed by the library when done.
		 */
		InputStream open(String name) throws IOException;
	}
}
