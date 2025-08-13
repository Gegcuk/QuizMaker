package uk.gegc.quizmaker.util;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

public final class TestImageFactory {

	private TestImageFactory() {}

	public static byte[] makePng(int width, int height, Color fill, Color marker) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setColor(fill != null ? fill : Color.GRAY);
			g.fillRect(0, 0, width, height);
			if (marker != null) {
				g.setColor(marker);
				g.fillRect(0, 0, Math.max(1, width / 16), Math.max(1, height / 16));
			}
		} finally {
			g.dispose();
		}
		try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
			ImageIO.write(image, "png", baos);
			return baos.toByteArray();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static byte[] makeJpeg(int width, int height, float quality) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setColor(Color.LIGHT_GRAY);
			g.fillRect(0, 0, width, height);
			g.setColor(Color.DARK_GRAY);
			g.drawLine(0, 0, width, height);
			g.drawLine(0, height, width, 0);
		} finally {
			g.dispose();
		}
		try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
			Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
			if (!writers.hasNext()) throw new IllegalStateException("No JPEG writer available");
			ImageWriter writer = writers.next();
			ImageWriteParam param = writer.getDefaultWriteParam();
			if (param.canWriteCompressed()) {
				param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
				param.setCompressionQuality(Math.max(0.0f, Math.min(1.0f, quality)));
			}
			try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
				writer.setOutput(ios);
				writer.write(null, new IIOImage(image, null, null), param);
				writer.dispose();
			}
			return baos.toByteArray();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static boolean isPng(byte[] bytes) {
		if (bytes == null || bytes.length < 8) return false;
		return (bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47
				&& bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A;
	}
}


