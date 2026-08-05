/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet.internal.mcp.schema;

import org.jspecify.annotations.NonNull;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Reads enum constant names directly from classfile field metadata so their
 * declaration order is retained without initializing the enum class.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpRuntimeEnumNameReader {
	private static final int CLASS_FILE_MAGIC = 0xCAFEBABE;
	private static final int ACCESS_ENUM = 0x4000;
	private static final int MAXIMUM_CLASS_FILE_SIZE_IN_BYTES = 16 * 1024 * 1024;

	private final int maximumConstantCount;
	private final int maximumNameLengthInCharacters;

	McpRuntimeEnumNameReader(int maximumConstantCount,
			int maximumNameLengthInCharacters) {
		this.maximumConstantCount = requirePositive(maximumConstantCount,
				"maximumConstantCount");
		this.maximumNameLengthInCharacters = requirePositive(
				maximumNameLengthInCharacters,
				"maximumNameLengthInCharacters");
	}

	@NonNull
	List<@NonNull String> read(@NonNull Class<?> enumType) {
		requireNonNull(enumType);
		if (!enumType.isEnum())
			throw failure("The supplied runtime type is not an enum.");

		byte[] classFile = readClassFile(enumType);
		List<String> names = parseClassFile(classFile, enumType);
		verifyLoadedEnumMetadata(enumType, names);
		return List.copyOf(names);
	}

	private byte @NonNull [] readClassFile(@NonNull Class<?> enumType) {
		String resourceName = "/" + enumType.getName().replace('.', '/')
				+ ".class";
		try (InputStream input = enumType.getResourceAsStream(resourceName)) {
			if (input == null)
				throw failure("Enum classfile metadata is unavailable.");
			byte[] bytes = input.readNBytes(MAXIMUM_CLASS_FILE_SIZE_IN_BYTES + 1);
			if (bytes.length > MAXIMUM_CLASS_FILE_SIZE_IN_BYTES)
				throw failure("Enum classfile metadata exceeds its size limit.");
			return bytes;
		} catch (IOException exception) {
			throw failure("Unable to read enum classfile metadata.");
		}
	}

	@NonNull
	private List<@NonNull String> parseClassFile(byte @NonNull [] classFile,
			@NonNull Class<?> enumType) {
		Cursor cursor = new Cursor(classFile);
		if (cursor.readU4() != Integer.toUnsignedLong(CLASS_FILE_MAGIC))
			throw failure("Enum classfile metadata has an invalid magic value.");
		cursor.skip(4); // minor_version and major_version

		int constantPoolCount = cursor.readU2();
		if (constantPoolCount == 0)
			throw failure("Enum classfile metadata has an invalid constant pool.");
		int[] utf8Offsets = new int[constantPoolCount];
		int[] utf8Lengths = new int[constantPoolCount];
		int[] classNameIndexes = new int[constantPoolCount];
		for (int index = 1; index < constantPoolCount; ++index) {
			int tag = cursor.readU1();
			switch (tag) {
				case 1 -> {
					int length = cursor.readU2();
					utf8Offsets[index] = cursor.position();
					utf8Lengths[index] = length;
					cursor.skip(length);
				}
				case 3, 4, 9, 10, 11, 12, 17, 18 -> cursor.skip(4);
				case 5, 6 -> {
					cursor.skip(8);
					if (++index >= constantPoolCount)
						throw failure(
								"Enum classfile metadata has an invalid constant pool.");
				}
				case 7 -> classNameIndexes[index] = cursor.readU2();
				case 8, 16, 19, 20 -> cursor.skip(2);
				case 15 -> cursor.skip(3);
				default -> throw failure(
						"Enum classfile metadata has an unsupported constant-pool entry.");
			}
		}

		int accessFlags = cursor.readU2();
		if ((accessFlags & ACCESS_ENUM) == 0)
			throw failure("Classfile metadata does not describe an enum.");
		int thisClassIndex = cursor.readU2();
		cursor.skip(2); // super_class
		String className = readClassName(classFile, thisClassIndex,
				classNameIndexes, utf8Offsets, utf8Lengths);
		if (!enumType.getName().replace('.', '/').equals(className))
			throw failure("Enum classfile metadata names a different class.");

		int interfaceCount = cursor.readU2();
		cursor.skip((long) interfaceCount * 2);
		int fieldCount = cursor.readU2();
		List<String> names = new ArrayList<>(Math.min(fieldCount,
				maximumConstantCount));
		Set<String> uniqueNames = new LinkedHashSet<>();
		for (int index = 0; index < fieldCount; ++index) {
			int fieldAccessFlags = cursor.readU2();
			int nameIndex = cursor.readU2();
			cursor.skip(2); // descriptor_index
			int attributeCount = cursor.readU2();

			if ((fieldAccessFlags & ACCESS_ENUM) != 0) {
				if (names.size() >= maximumConstantCount)
					throw limit(
							McpSchemaCompilationException.Limit.COLLECTION_ENTRY_COUNT,
							"Enum constant count exceeds its configured limit.");
				String name = readUtf8(classFile, nameIndex, utf8Offsets,
						utf8Lengths, maximumNameLengthInCharacters,
						"Enum constant name exceeds its configured limit.");
				if (!uniqueNames.add(name))
					throw failure("Enum classfile metadata contains a duplicate constant.");
				names.add(name);
			}

			for (int attribute = 0; attribute < attributeCount; ++attribute) {
				cursor.skip(2); // attribute_name_index
				cursor.skip(cursor.readU4());
			}
		}
		return names;
	}

	@NonNull
	private String readClassName(byte @NonNull [] classFile, int classIndex,
			int @NonNull [] classNameIndexes, int @NonNull [] utf8Offsets,
			int @NonNull [] utf8Lengths) {
		if (classIndex <= 0 || classIndex >= classNameIndexes.length)
			throw failure("Enum classfile metadata has an invalid class name.");
		int nameIndex = classNameIndexes[classIndex];
		return readUtf8(classFile, nameIndex, utf8Offsets, utf8Lengths,
				classFile.length, "Enum class name exceeds the classfile bound.");
	}

	@NonNull
	private String readUtf8(byte @NonNull [] classFile, int index,
			int @NonNull [] utf8Offsets, int @NonNull [] utf8Lengths,
			int maximumLengthInCharacters,
			@NonNull String lengthFailureMessage) {
		if (index <= 0 || index >= utf8Offsets.length || utf8Offsets[index] == 0)
			throw failure("Enum classfile metadata has an invalid UTF-8 reference.");
		int offset = utf8Offsets[index];
		int length = utf8Lengths[index];
		if (modifiedUtf8CharacterCount(classFile, offset, length,
				maximumLengthInCharacters)
				> maximumLengthInCharacters)
			throw limit(McpSchemaCompilationException.Limit.NAME_LENGTH,
					lengthFailureMessage);

		try (DataInputStream input = new DataInputStream(
				new ByteArrayInputStream(classFile, offset - 2, length + 2))) {
			return input.readUTF();
		} catch (IOException exception) {
			throw failure("Enum classfile metadata contains malformed UTF-8.");
		}
	}

	private int modifiedUtf8CharacterCount(byte @NonNull [] bytes, int offset,
			int length,
			int maximumLengthInCharacters) {
		int end = offset + length;
		int count = 0;
		for (int index = offset; index < end; ++count) {
			int first = bytes[index] & 0xFF;
			if ((first & 0x80) == 0) {
				index++;
			} else if ((first & 0xE0) == 0xC0) {
				requireContinuation(bytes, index + 1, end);
				index += 2;
			} else if ((first & 0xF0) == 0xE0) {
				requireContinuation(bytes, index + 1, end);
				requireContinuation(bytes, index + 2, end);
				index += 3;
			} else {
				throw failure("Enum classfile metadata contains malformed UTF-8.");
			}
			if (count >= maximumLengthInCharacters)
				return maximumLengthInCharacters + 1;
		}
		return count;
	}

	private void requireContinuation(byte @NonNull [] bytes, int index,
			int end) {
		if (index >= end || (bytes[index] & 0xC0) != 0x80)
			throw failure("Enum classfile metadata contains malformed UTF-8.");
	}

	void verifyLoadedEnumMetadata(@NonNull Class<?> enumType,
			@NonNull List<@NonNull String> names) {
		List<String> reflectedNames = new ArrayList<>();
		try {
			for (Field field : enumType.getDeclaredFields()) {
				if (field.isEnumConstant())
					reflectedNames.add(field.getName());
			}
		} catch (LinkageError | RuntimeException exception) {
			throw failure("Unable to verify loaded enum metadata.");
		}
		if (!reflectedNames.equals(names))
			throw failure("Loaded enum metadata does not match its classfile.");
	}

	private static int requirePositive(int value, @NonNull String name) {
		if (value <= 0)
			throw new IllegalArgumentException(name + " must be positive.");
		return value;
	}

	@NonNull
	private static IllegalArgumentException failure(@NonNull String message) {
		return new IllegalArgumentException(message);
	}

	@NonNull
	private static McpTypedTypeModelLimitException limit(
			McpSchemaCompilationException.@NonNull Limit limit,
			@NonNull String message) {
		return new McpTypedTypeModelLimitException(limit, message);
	}

	@NotThreadSafe
	private static final class Cursor {
		private final byte @NonNull [] bytes;
		private int position;

		private Cursor(byte @NonNull [] bytes) {
			this.bytes = requireNonNull(bytes);
		}

		private int position() {
			return position;
		}

		private int readU1() {
			requireRemaining(1);
			return bytes[position++] & 0xFF;
		}

		private int readU2() {
			return (readU1() << 8) | readU1();
		}

		private long readU4() {
			return ((long) readU1() << 24)
					| ((long) readU1() << 16)
					| ((long) readU1() << 8)
					| readU1();
		}

		private void skip(long count) {
			if (count < 0 || count > Integer.MAX_VALUE)
				throw failure("Enum classfile metadata has an invalid length.");
			requireRemaining((int) count);
			position += (int) count;
		}

		private void requireRemaining(int count) {
			if (count < 0 || count > bytes.length - position)
				throw failure("Enum classfile metadata is truncated.");
		}
	}
}
