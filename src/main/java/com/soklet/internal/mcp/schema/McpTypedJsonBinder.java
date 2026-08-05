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

import com.soklet.internal.mcp.protocol.McpJsonArray;
import com.soklet.internal.mcp.protocol.McpJsonBoolean;
import com.soklet.internal.mcp.protocol.McpJsonNull;
import com.soklet.internal.mcp.protocol.McpJsonNumber;
import com.soklet.internal.mcp.protocol.McpJsonObject;
import com.soklet.internal.mcp.protocol.McpJsonString;
import com.soklet.internal.mcp.protocol.McpJsonValue;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static java.util.Objects.requireNonNull;

/**
 * Stateless intrinsic JSON binder for the closed typed-schema profile.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class McpTypedJsonBinder {
	@NonNull
	private final McpTypedJsonBindingLimits limits;

	McpTypedJsonBinder() {
		this(McpTypedJsonBindingLimits.productionDefaults());
	}

	McpTypedJsonBinder(@NonNull McpTypedJsonBindingLimits limits) {
		this.limits = requireNonNull(limits);
	}

	@SuppressWarnings("unchecked")
	@NonNull
	<T> T fromJson(@Nullable McpJsonValue value,
			@NonNull McpTypedJsonBinding<T> binding) {
		requireNonNull(binding);
		ConversionContext context = new ConversionContext(
				McpTypedJsonBindingException.Operation.FROM_JSON);
		return (T) read(value, binding.rootNode(), McpTypedSchemaPath.root(),
				1, false, context);
	}

	@NonNull
	<T> McpJsonValue toJson(@Nullable T value,
			@NonNull McpTypedJsonBinding<T> binding) {
		requireNonNull(binding);
		ConversionContext context = new ConversionContext(
				McpTypedJsonBindingException.Operation.TO_JSON);
		return write(value, binding.rootNode(), McpTypedSchemaPath.root(), 1,
				false, context);
	}

	@NonNull
	private Object read(@Nullable McpJsonValue value,
			@NonNull McpTypedJsonBindingNode node,
			@NonNull McpTypedSchemaPath path, int depth,
			boolean nodePrecharged, @NonNull ConversionContext context) {
		context.enterValue(depth, nodePrecharged, path);
		if (value == null || value instanceof McpJsonNull)
			throw failure(McpTypedJsonBindingException.Operation.FROM_JSON,
					McpTypedJsonBindingException.Reason.NULL_VALUE, path);
		if (node instanceof McpTypedJsonBindingNode.Scalar scalar)
			return readScalar(value, scalar, path);
		if (node instanceof McpTypedJsonBindingNode.Enumeration enumeration)
			return readEnumeration(value, enumeration, path);
		if (node instanceof McpTypedJsonBindingNode.ArrayValue array)
			return readArray(value, array, path, depth, context);
		if (node instanceof McpTypedJsonBindingNode.ListValue list)
			return readList(value, list, path, depth, context);
		if (node instanceof McpTypedJsonBindingNode.MapValue map)
			return readMap(value, map, path, depth, context);
		if (node instanceof McpTypedJsonBindingNode.RecordValue record)
			return readRecord(value, record, path, depth, context);
		throw failure(McpTypedJsonBindingException.Operation.FROM_JSON,
				McpTypedJsonBindingException.Reason.JAVA_TYPE_MISMATCH, path);
	}

	@NonNull
	private Object readScalar(@NonNull McpJsonValue value,
			McpTypedJsonBindingNode.@NonNull Scalar binding,
			@NonNull McpTypedSchemaPath path) {
		if (binding.scalar() == McpTypedSchemaScalar.BOOLEAN) {
			if (!(value instanceof McpJsonBoolean booleanValue))
				throw jsonType(path);
			return booleanValue == McpJsonBoolean.TRUE;
		}
		if (binding.scalar() == McpTypedSchemaScalar.STRING) {
			if (!(value instanceof McpJsonString string))
				throw jsonType(path);
			return string.value();
		}
		if (!(value instanceof McpJsonNumber number))
			throw jsonType(path);

		BigDecimal decimal = number.value();
		return switch (binding.scalar()) {
			case BYTE -> boundedInteger(decimal, McpTypedSchemaScalar.BYTE, path)
					.byteValue();
			case SHORT -> boundedInteger(decimal, McpTypedSchemaScalar.SHORT, path)
					.shortValue();
			case INT -> boundedInteger(decimal, McpTypedSchemaScalar.INT, path)
					.intValue();
			case LONG -> boundedInteger(decimal, McpTypedSchemaScalar.LONG, path)
					.longValue();
			case BIG_INTEGER -> integral(decimal, path);
			case FLOAT -> finiteFloat(decimal, path);
			case DOUBLE -> finiteDouble(decimal, path);
			case BIG_DECIMAL -> decimal;
			case BOOLEAN, STRING -> throw new AssertionError(
					"Scalar dispatch invariant violated.");
		};
	}

	@NonNull
	private BigInteger boundedInteger(@NonNull BigDecimal value,
			@NonNull McpTypedSchemaScalar scalar,
			@NonNull McpTypedSchemaPath path) {
		BigInteger integer = integral(value, path);
		BigInteger minimum = scalar.minimum().orElseThrow().toBigIntegerExact();
		BigInteger maximum = scalar.maximum().orElseThrow().toBigIntegerExact();
		if (integer.compareTo(minimum) < 0 || integer.compareTo(maximum) > 0)
			throw failure(McpTypedJsonBindingException.Operation.FROM_JSON,
					McpTypedJsonBindingException.Reason.NUMBER_OUT_OF_RANGE, path);
		return integer;
	}

	@NonNull
	private BigInteger integral(@NonNull BigDecimal value,
			@NonNull McpTypedSchemaPath path) {
		try {
			return value.toBigIntegerExact();
		} catch (ArithmeticException exception) {
			throw failure(McpTypedJsonBindingException.Operation.FROM_JSON,
					McpTypedJsonBindingException.Reason.NON_INTEGER_NUMBER, path);
		}
	}

	@NonNull
	private Float finiteFloat(@NonNull BigDecimal value,
			@NonNull McpTypedSchemaPath path) {
		float result = value.floatValue();
		if (!Float.isFinite(result))
			throw failure(McpTypedJsonBindingException.Operation.FROM_JSON,
					McpTypedJsonBindingException.Reason.NUMBER_OUT_OF_RANGE, path);
		return result;
	}

	@NonNull
	private Double finiteDouble(@NonNull BigDecimal value,
			@NonNull McpTypedSchemaPath path) {
		double result = value.doubleValue();
		if (!Double.isFinite(result))
			throw failure(McpTypedJsonBindingException.Operation.FROM_JSON,
					McpTypedJsonBindingException.Reason.NUMBER_OUT_OF_RANGE, path);
		return result;
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@NonNull
	private Object readEnumeration(@NonNull McpJsonValue value,
			McpTypedJsonBindingNode.@NonNull Enumeration binding,
			@NonNull McpTypedSchemaPath path) {
		if (!(value instanceof McpJsonString string))
			throw jsonType(path);
		if (!binding.constantNames().contains(string.value()))
			throw failure(McpTypedJsonBindingException.Operation.FROM_JSON,
					McpTypedJsonBindingException.Reason.ENUM_CONSTANT_MISMATCH,
					path);
		try {
			return Enum.valueOf((Class) binding.enumType(), string.value());
		} catch (RuntimeException | LinkageError exception) {
			throw failure(McpTypedJsonBindingException.Operation.FROM_JSON,
					McpTypedJsonBindingException.Reason.ENUM_CONSTANT_MISMATCH,
					path);
		}
	}

	@NonNull
	private Object readArray(@NonNull McpJsonValue value,
			McpTypedJsonBindingNode.@NonNull ArrayValue binding,
			@NonNull McpTypedSchemaPath path, int depth,
			@NonNull ConversionContext context) {
		if (!(value instanceof McpJsonArray array))
			throw jsonType(path);
		context.enterComposite(array, path);
		try {
			int size = array.values().size();
			context.prechargeContainer(size, path);
			Object result;
			try {
				result = Array.newInstance(binding.componentType(), size);
			} catch (RuntimeException exception) {
				throw javaType(McpTypedJsonBindingException.Operation.FROM_JSON,
						path);
			}
			for (int index = 0; index < size; ++index) {
				Object element = read(array.values().get(index),
						binding.elementNode(), path.arrayElement(), depth + 1,
						true, context);
				try {
					Array.set(result, index, element);
				} catch (RuntimeException exception) {
					throw javaType(McpTypedJsonBindingException.Operation.FROM_JSON,
							path.arrayElement());
				}
			}
			return result;
		} finally {
			context.exitComposite(array);
		}
	}

	@NonNull
	private Object readList(@NonNull McpJsonValue value,
			McpTypedJsonBindingNode.@NonNull ListValue binding,
			@NonNull McpTypedSchemaPath path, int depth,
			@NonNull ConversionContext context) {
		if (!(value instanceof McpJsonArray array))
			throw jsonType(path);
		context.enterComposite(array, path);
		try {
			int size = array.values().size();
			context.prechargeContainer(size, path);
			List<Object> result = new ArrayList<>(size);
			for (McpJsonValue element : array.values())
				result.add(read(element, binding.elementNode(),
						path.arrayElement(), depth + 1, true, context));
			return List.copyOf(result);
		} finally {
			context.exitComposite(array);
		}
	}

	@NonNull
	private Object readMap(@NonNull McpJsonValue value,
			McpTypedJsonBindingNode.@NonNull MapValue binding,
			@NonNull McpTypedSchemaPath path, int depth,
			@NonNull ConversionContext context) {
		if (!(value instanceof McpJsonObject object))
			throw jsonType(path);
		context.enterComposite(object, path);
		try {
			int size = object.members().size();
			context.prechargeContainer(size, path);
			Map<String, Object> result = new LinkedHashMap<>(size);
			for (Map.Entry<String, McpJsonValue> entry
					: object.members().entrySet())
				result.put(entry.getKey(), read(entry.getValue(),
						binding.valueNode(), path.mapValue(), depth + 1, true,
						context));
			return Collections.unmodifiableMap(result);
		} finally {
			context.exitComposite(object);
		}
	}

	@NonNull
	private Object readRecord(@NonNull McpJsonValue value,
			McpTypedJsonBindingNode.@NonNull RecordValue binding,
			@NonNull McpTypedSchemaPath path, int depth,
			@NonNull ConversionContext context) {
		if (!(value instanceof McpJsonObject object))
			throw jsonType(path);
		context.enterComposite(object, path);
		try {
			context.prechargeContainer(object.members().size(), path);
			context.checkContainerEntryCount(binding.properties().size(), path);
			for (String memberName : object.members().keySet()) {
				if (!binding.propertyNames().contains(memberName))
					throw failure(
							McpTypedJsonBindingException.Operation.FROM_JSON,
							McpTypedJsonBindingException.Reason.UNKNOWN_PROPERTY,
							path);
			}

			Object[] arguments = new Object[binding.properties().size()];
			for (int index = 0; index < binding.properties().size(); ++index) {
				McpTypedJsonBindingNode.Property property =
						binding.properties().get(index);
				McpTypedSchemaPath propertyPath = path.property(property.name());
				McpJsonValue propertyValue = object.members().get(property.name());
				if (propertyValue == null) {
					if (property.optional()) {
						arguments[index] = Optional.empty();
						continue;
					}
					throw failure(McpTypedJsonBindingException.Operation.FROM_JSON,
							McpTypedJsonBindingException.Reason.REQUIRED_PROPERTY_MISSING,
							propertyPath);
				}
				Object converted = read(propertyValue, property.valueNode(),
						propertyPath, depth + 1, true, context);
				arguments[index] = property.optional() ? Optional.of(converted)
						: converted;
			}

			try {
				return binding.constructor().newInstance(arguments);
			} catch (ReflectiveOperationException | RuntimeException
					| LinkageError exception) {
				throw failure(McpTypedJsonBindingException.Operation.FROM_JSON,
						McpTypedJsonBindingException.Reason.RECORD_CONSTRUCTION_FAILED,
						path);
			}
		} finally {
			context.exitComposite(object);
		}
	}

	@NonNull
	private McpJsonValue write(@Nullable Object value,
			@NonNull McpTypedJsonBindingNode node,
			@NonNull McpTypedSchemaPath path, int depth,
			boolean nodePrecharged, @NonNull ConversionContext context) {
		context.enterValue(depth, nodePrecharged, path);
		if (value == null)
			throw failure(McpTypedJsonBindingException.Operation.TO_JSON,
					McpTypedJsonBindingException.Reason.NULL_VALUE, path);
		if (node instanceof McpTypedJsonBindingNode.Scalar scalar)
			return writeScalar(value, scalar, path);
		if (node instanceof McpTypedJsonBindingNode.Enumeration enumeration)
			return writeEnumeration(value, enumeration, path);
		if (node instanceof McpTypedJsonBindingNode.ArrayValue array)
			return writeArray(value, array, path, depth, context);
		if (node instanceof McpTypedJsonBindingNode.ListValue list)
			return writeList(value, list, path, depth, context);
		if (node instanceof McpTypedJsonBindingNode.MapValue map)
			return writeMap(value, map, path, depth, context);
		if (node instanceof McpTypedJsonBindingNode.RecordValue record)
			return writeRecord(value, record, path, depth, context);
		throw javaType(McpTypedJsonBindingException.Operation.TO_JSON, path);
	}

	@NonNull
	private McpJsonValue writeScalar(@NonNull Object value,
			McpTypedJsonBindingNode.@NonNull Scalar binding,
			@NonNull McpTypedSchemaPath path) {
		if (value.getClass() != binding.javaType())
			throw javaType(McpTypedJsonBindingException.Operation.TO_JSON, path);
		return switch (binding.scalar()) {
			case BOOLEAN -> McpJsonBoolean.fromBoolean((Boolean) value);
			case BYTE -> new McpJsonNumber(((Byte) value).longValue());
			case SHORT -> new McpJsonNumber(((Short) value).longValue());
			case INT -> new McpJsonNumber(((Integer) value).longValue());
			case LONG -> new McpJsonNumber((Long) value);
			case BIG_INTEGER -> new McpJsonNumber(new BigDecimal(
					(BigInteger) value));
			case FLOAT -> jsonFloat((Float) value, path);
			case DOUBLE -> jsonDouble((Double) value, path);
			case BIG_DECIMAL -> new McpJsonNumber((BigDecimal) value);
			case STRING -> new McpJsonString((String) value);
		};
	}

	@NonNull
	private McpJsonNumber jsonFloat(float value,
			@NonNull McpTypedSchemaPath path) {
		if (!Float.isFinite(value))
			throw failure(McpTypedJsonBindingException.Operation.TO_JSON,
					McpTypedJsonBindingException.Reason.NON_FINITE_NUMBER, path);
		return new McpJsonNumber(new BigDecimal(Float.toString(value)));
	}

	@NonNull
	private McpJsonNumber jsonDouble(double value,
			@NonNull McpTypedSchemaPath path) {
		if (!Double.isFinite(value))
			throw failure(McpTypedJsonBindingException.Operation.TO_JSON,
					McpTypedJsonBindingException.Reason.NON_FINITE_NUMBER, path);
		return new McpJsonNumber(new BigDecimal(Double.toString(value)));
	}

	@NonNull
	private McpJsonValue writeEnumeration(@NonNull Object value,
			McpTypedJsonBindingNode.@NonNull Enumeration binding,
			@NonNull McpTypedSchemaPath path) {
		if (!binding.enumType().isInstance(value))
			throw javaType(McpTypedJsonBindingException.Operation.TO_JSON, path);
		String name = ((Enum<?>) value).name();
		if (!binding.constantNames().contains(name))
			throw failure(McpTypedJsonBindingException.Operation.TO_JSON,
					McpTypedJsonBindingException.Reason.ENUM_CONSTANT_MISMATCH,
					path);
		return new McpJsonString(name);
	}

	@NonNull
	private McpJsonValue writeArray(@NonNull Object value,
			McpTypedJsonBindingNode.@NonNull ArrayValue binding,
			@NonNull McpTypedSchemaPath path, int depth,
			@NonNull ConversionContext context) {
		if (value.getClass() != binding.arrayType())
			throw javaType(McpTypedJsonBindingException.Operation.TO_JSON, path);
		context.enterComposite(value, path);
		try {
			int length;
			try {
				length = Array.getLength(value);
			} catch (RuntimeException exception) {
				throw javaType(McpTypedJsonBindingException.Operation.TO_JSON,
						path);
			}
			context.prechargeContainer(length, path);
			List<McpJsonValue> values = new ArrayList<>(length);
			for (int index = 0; index < length; ++index) {
				Object element;
				try {
					element = Array.get(value, index);
				} catch (RuntimeException exception) {
					throw javaType(McpTypedJsonBindingException.Operation.TO_JSON,
							path.arrayElement());
				}
				values.add(write(element, binding.elementNode(),
						path.arrayElement(), depth + 1, true, context));
			}
			return new McpJsonArray(values);
		} finally {
			context.exitComposite(value);
		}
	}

	@NonNull
	private McpJsonValue writeList(@NonNull Object value,
			McpTypedJsonBindingNode.@NonNull ListValue binding,
			@NonNull McpTypedSchemaPath path, int depth,
			@NonNull ConversionContext context) {
		if (!(value instanceof List<?> list))
			throw javaType(McpTypedJsonBindingException.Operation.TO_JSON, path);
		context.enterComposite(value, path);
		try {
			int size = list.size();
			context.prechargeContainer(size, path);
			List<McpJsonValue> values = new ArrayList<>(size);
			Iterator<?> iterator = list.iterator();
			for (int index = 0; index < size; ++index) {
				if (!iterator.hasNext())
					throw mutated(path);
				values.add(write(iterator.next(), binding.elementNode(),
						path.arrayElement(), depth + 1, true, context));
			}
			if (iterator.hasNext() || list.size() != size)
				throw mutated(path);
			return new McpJsonArray(values);
		} catch (McpTypedJsonBindingException exception) {
			throw exception;
		} catch (RuntimeException | LinkageError exception) {
			throw container(path);
		} finally {
			context.exitComposite(value);
		}
	}

	@NonNull
	private McpJsonValue writeMap(@NonNull Object value,
			McpTypedJsonBindingNode.@NonNull MapValue binding,
			@NonNull McpTypedSchemaPath path, int depth,
			@NonNull ConversionContext context) {
		if (!(value instanceof Map<?, ?> map))
			throw javaType(McpTypedJsonBindingException.Operation.TO_JSON, path);
		context.enterComposite(value, path);
		try {
			int size = map.size();
			context.prechargeContainer(size, path);
			Map<String, Object> sorted = new TreeMap<>();
			Iterator<? extends Map.Entry<?, ?>> iterator =
					map.entrySet().iterator();
			for (int index = 0; index < size; ++index) {
				if (!iterator.hasNext())
					throw mutated(path);
				Map.Entry<?, ?> entry = iterator.next();
				if (!(entry.getKey() instanceof String key))
					throw javaType(McpTypedJsonBindingException.Operation.TO_JSON,
							path);
				if (sorted.containsKey(key))
					throw mutated(path);
				sorted.put(key, entry.getValue());
			}
			if (iterator.hasNext() || map.size() != size)
				throw mutated(path);
			Map<String, McpJsonValue> members = new LinkedHashMap<>(sorted.size());
			for (Map.Entry<String, Object> entry : sorted.entrySet())
				members.put(entry.getKey(), write(entry.getValue(),
						binding.valueNode(), path.mapValue(), depth + 1, true,
						context));
			return new McpJsonObject(members);
		} catch (McpTypedJsonBindingException exception) {
			throw exception;
		} catch (RuntimeException | LinkageError exception) {
			throw container(path);
		} finally {
			context.exitComposite(value);
		}
	}

	@NonNull
	private McpJsonValue writeRecord(@NonNull Object value,
			McpTypedJsonBindingNode.@NonNull RecordValue binding,
			@NonNull McpTypedSchemaPath path, int depth,
			@NonNull ConversionContext context) {
		if (!binding.recordType().isInstance(value))
			throw javaType(McpTypedJsonBindingException.Operation.TO_JSON, path);
		context.enterComposite(value, path);
		try {
			context.checkContainerEntryCount(binding.properties().size(), path);
			Object[] values = new Object[binding.properties().size()];
			boolean[] present = new boolean[binding.properties().size()];
			int presentCount = 0;
			for (int index = 0; index < binding.properties().size(); ++index) {
				McpTypedJsonBindingNode.Property property =
						binding.properties().get(index);
				McpTypedSchemaPath propertyPath = path.property(property.name());
				Object propertyValue;
				try {
					propertyValue = property.accessor().invoke(value);
				} catch (IllegalAccessException | InvocationTargetException
						| RuntimeException | LinkageError exception) {
					throw failure(McpTypedJsonBindingException.Operation.TO_JSON,
							McpTypedJsonBindingException.Reason.RECORD_ACCESSOR_FAILED,
							propertyPath);
				}
				if (!property.optional()) {
					values[index] = propertyValue;
					present[index] = true;
					presentCount++;
					continue;
				}
				if (propertyValue == null)
					throw failure(McpTypedJsonBindingException.Operation.TO_JSON,
							McpTypedJsonBindingException.Reason.NULL_VALUE,
							propertyPath);
				if (!(propertyValue instanceof Optional<?> optional))
					throw javaType(McpTypedJsonBindingException.Operation.TO_JSON,
							propertyPath);
				if (optional.isPresent()) {
					values[index] = optional.get();
					present[index] = true;
					presentCount++;
				}
			}

			context.prechargeContainer(presentCount, path);
			Map<String, McpJsonValue> members = new LinkedHashMap<>(presentCount);
			for (int index = 0; index < binding.properties().size(); ++index) {
				if (!present[index])
					continue;
				McpTypedJsonBindingNode.Property property =
						binding.properties().get(index);
				McpTypedSchemaPath propertyPath = path.property(property.name());
				members.put(property.name(), write(values[index],
						property.valueNode(), propertyPath, depth + 1, true,
						context));
			}
			return new McpJsonObject(members);
		} finally {
			context.exitComposite(value);
		}
	}

	@NonNull
	private McpTypedJsonBindingException jsonType(
			@NonNull McpTypedSchemaPath path) {
		return failure(McpTypedJsonBindingException.Operation.FROM_JSON,
				McpTypedJsonBindingException.Reason.JSON_TYPE_MISMATCH, path);
	}

	@NonNull
	private McpTypedJsonBindingException javaType(
			McpTypedJsonBindingException.@NonNull Operation operation,
			@NonNull McpTypedSchemaPath path) {
		return failure(operation,
				McpTypedJsonBindingException.Reason.JAVA_TYPE_MISMATCH, path);
	}

	@NonNull
	private McpTypedJsonBindingException container(
			@NonNull McpTypedSchemaPath path) {
		return failure(McpTypedJsonBindingException.Operation.TO_JSON,
				McpTypedJsonBindingException.Reason.CONTAINER_ACCESS_FAILED, path);
	}

	@NonNull
	private McpTypedJsonBindingException mutated(
			@NonNull McpTypedSchemaPath path) {
		return failure(McpTypedJsonBindingException.Operation.TO_JSON,
				McpTypedJsonBindingException.Reason.CONTAINER_MUTATED, path);
	}

	@NonNull
	private McpTypedJsonBindingException failure(
			McpTypedJsonBindingException.@NonNull Operation operation,
			McpTypedJsonBindingException.@NonNull Reason reason,
			@NonNull McpTypedSchemaPath path) {
		return new McpTypedJsonBindingException(operation, reason, path);
	}

	@NotThreadSafe
	private final class ConversionContext {
		private final McpTypedJsonBindingException.@NonNull Operation operation;
		@NonNull
		private final IdentityHashMap<@NonNull Object, @NonNull Boolean> activeComposites =
				new IdentityHashMap<>();
		private int nodeCount;

		private ConversionContext(
				McpTypedJsonBindingException.@NonNull Operation operation) {
			this.operation = requireNonNull(operation);
		}

		private void enterValue(int depth, boolean nodePrecharged,
				@NonNull McpTypedSchemaPath path) {
			if (depth > limits.maximumNestingDepth())
				throw limit(
						McpTypedJsonBindingException.Limit.NESTING_DEPTH, path);
			if (!nodePrecharged)
				chargeNodes(1, path);
		}

		private void prechargeContainer(int entryCount,
				@NonNull McpTypedSchemaPath path) {
			checkContainerEntryCount(entryCount, path);
			chargeNodes(entryCount, path);
		}

		private void checkContainerEntryCount(int entryCount,
				@NonNull McpTypedSchemaPath path) {
			if (entryCount < 0)
				throw failure(operation,
						McpTypedJsonBindingException.Reason.CONTAINER_MUTATED,
						path);
			if (entryCount > limits.maximumContainerEntryCount())
				throw limit(
						McpTypedJsonBindingException.Limit.CONTAINER_ENTRY_COUNT,
						path);
		}

		private void chargeNodes(int count,
				@NonNull McpTypedSchemaPath path) {
			if (count < 0)
				throw failure(operation,
						McpTypedJsonBindingException.Reason.CONTAINER_MUTATED,
						path);
			if ((long) nodeCount + count > limits.maximumNodeCount())
				throw limit(McpTypedJsonBindingException.Limit.NODE_COUNT, path);
			nodeCount += count;
		}

		private void enterComposite(@NonNull Object value,
				@NonNull McpTypedSchemaPath path) {
			if (activeComposites.put(requireNonNull(value), Boolean.TRUE) != null)
				throw failure(operation,
						McpTypedJsonBindingException.Reason.CYCLIC_VALUE, path);
		}

		private void exitComposite(@NonNull Object value) {
			activeComposites.remove(value);
		}

		@NonNull
		private McpTypedJsonBindingException limit(
				McpTypedJsonBindingException.@NonNull Limit limit,
				@NonNull McpTypedSchemaPath path) {
			return new McpTypedJsonBindingException(operation, limit, path);
		}
	}
}
