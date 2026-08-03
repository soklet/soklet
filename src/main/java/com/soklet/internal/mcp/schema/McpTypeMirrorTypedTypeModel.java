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

import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/** Compile-time type adapter for the shared typed-schema policy resolver. */
final class McpTypeMirrorTypedTypeModel
		implements McpTypedTypeModel<TypeMirror> {
	private static final String BOOLEAN = "java.lang.Boolean";
	private static final String BYTE = "java.lang.Byte";
	private static final String SHORT = "java.lang.Short";
	private static final String INTEGER = "java.lang.Integer";
	private static final String LONG = "java.lang.Long";
	private static final String FLOAT = "java.lang.Float";
	private static final String DOUBLE = "java.lang.Double";
	private static final String BIG_INTEGER = "java.math.BigInteger";
	private static final String BIG_DECIMAL = "java.math.BigDecimal";
	private static final String STRING = "java.lang.String";
	private static final String OBJECT = "java.lang.Object";
	private static final String CHAR_SEQUENCE = "java.lang.CharSequence";
	private static final String LIST = "java.util.List";
	private static final String MAP = "java.util.Map";
	private static final String OPTIONAL = "java.util.Optional";
	private static final String INTERNAL_JSON_VALUE =
			"com.soklet.internal.mcp.protocol.McpJsonValue";
	private static final Set<String> FRAMEWORK_ROOT_TYPE_NAMES = Set.of(
			"com.soklet.McpJsonValue",
			"com.soklet.McpOperationResult",
			"com.soklet.McpCompletePayload",
			"com.soklet.McpContentBlock",
			"com.soklet.McpResourceContents");

	private final Types types;
	private final Elements elements;
	private final McpSchemaCompilationLimits limits;
	private final Set<String> frameworkRootTypeNames;

	McpTypeMirrorTypedTypeModel(Types types, Elements elements,
			McpSchemaCompilationLimits limits) {
		this(types, elements, limits, FRAMEWORK_ROOT_TYPE_NAMES);
	}

	McpTypeMirrorTypedTypeModel(Types types, Elements elements,
			McpSchemaCompilationLimits limits,
			Set<String> frameworkRootTypeNames) {
		this.types = requireNonNull(types);
		this.elements = requireNonNull(elements);
		this.limits = requireNonNull(limits);
		this.frameworkRootTypeNames = Set.copyOf(
				requireNonNull(frameworkRootTypeNames));
		for (String frameworkRootTypeName : this.frameworkRootTypeNames)
			requireNonNull(frameworkRootTypeName);
	}

	@Override
	public McpTypedTypeDescriptor<TypeMirror> describe(TypeMirror type) {
		requireNonNull(type);
		McpTypedSchemaScalar scalar = scalar(type);
		if (scalar != null)
			return new McpTypedTypeDescriptor.Scalar<>(scalar);

		return switch (type.getKind()) {
			case ARRAY -> describeArray((ArrayType) type);
			case DECLARED -> describeDeclared((DeclaredType) type);
			case WILDCARD -> unsupported(
					McpTypedSchemaException.Reason.WILDCARD);
			case TYPEVAR -> unsupported(
					McpTypedSchemaException.Reason.UNRESOLVED_TYPE_VARIABLE);
			default -> unsupported(McpTypedSchemaException.Reason.UNSUPPORTED_TYPE);
		};
	}

	private McpTypedTypeDescriptor<TypeMirror> describeArray(ArrayType type) {
		TypeMirror component = requireNonNull(type.getComponentType());
		if (new GenericTraversal().containsUnresolved(component))
			return unsupported(McpTypedSchemaException.Reason
					.UNRESOLVED_GENERIC_ARRAY_COMPONENT);
		return new McpTypedTypeDescriptor.ArrayValue<>(component);
	}

	private McpTypedTypeDescriptor<TypeMirror> describeDeclared(
			DeclaredType type) {
		if (!(type.asElement() instanceof TypeElement declaration))
			return unsupported(McpTypedSchemaException.Reason.UNSUPPORTED_TYPE);
		String name = declaration.getQualifiedName().toString();
		if (OBJECT.equals(name))
			return unsupported(McpTypedSchemaException.Reason.OBJECT_TYPE);
		if (assignableTo(type, CHAR_SEQUENCE))
			return unsupported(McpTypedSchemaException.Reason.CHAR_SEQUENCE_TYPE);
		if (frameworkType(type))
			return unsupported(McpTypedSchemaException.Reason.FRAMEWORK_TYPE);

		List<? extends TypeMirror> arguments = typeArguments(type);
		if (LIST.equals(name))
			return arguments.size() == 1
					? new McpTypedTypeDescriptor.ListValue<>(arguments.get(0))
					: unsupported(McpTypedSchemaException.Reason.RAW_GENERIC);
		if (MAP.equals(name))
			return arguments.size() == 2
					? new McpTypedTypeDescriptor.MapValue<>(arguments.get(0),
							arguments.get(1))
					: unsupported(McpTypedSchemaException.Reason.RAW_GENERIC);
		if (OPTIONAL.equals(name))
			return arguments.size() == 1
					? new McpTypedTypeDescriptor.OptionalValue<>(arguments.get(0))
					: unsupported(McpTypedSchemaException.Reason.RAW_GENERIC);

		if (arguments.isEmpty() && !declaration.getTypeParameters().isEmpty())
			return unsupported(McpTypedSchemaException.Reason.RAW_GENERIC);
		if (declaration.getKind() == ElementKind.ENUM)
			return enumerationDescriptor(declaration);
		if (declaration.getKind() == ElementKind.RECORD)
			return recordDescriptor(type, declaration);
		return unsupported(McpTypedSchemaException.Reason.UNSUPPORTED_TYPE);
	}

	private McpTypedTypeDescriptor.Enumeration<TypeMirror> enumerationDescriptor(
			TypeElement declaration) {
		int constantCount = 0;
		for (Element enclosed : declaration.getEnclosedElements()) {
			if (enclosed.getKind() != ElementKind.ENUM_CONSTANT)
				continue;
			if (constantCount >= limits.maximumCollectionEntryCount())
				throw limit(
						McpSchemaCompilationException.Limit.COLLECTION_ENTRY_COUNT,
						"Enum constant count exceeds its configured limit.");
			if (enclosed.getSimpleName().length()
					> limits.maximumNameLengthInCharacters())
				throw limit(McpSchemaCompilationException.Limit.NAME_LENGTH,
						"Enum constant name exceeds its configured limit.");
			constantCount++;
		}

		List<String> constants = new ArrayList<>(constantCount);
		for (Element enclosed : declaration.getEnclosedElements()) {
			if (enclosed.getKind() == ElementKind.ENUM_CONSTANT)
				constants.add(enclosed.getSimpleName().toString());
		}
		return new McpTypedTypeDescriptor.Enumeration<>(
				binaryName(declaration), constants);
	}

	private McpTypedTypeDescriptor.RecordValue<TypeMirror> recordDescriptor(
			DeclaredType type, TypeElement declaration) {
		List<? extends TypeMirror> arguments = typeArguments(type);
		List<? extends TypeParameterElement> parameters =
				declaration.getTypeParameters();
		if (parameters.size() != arguments.size())
			throw new IllegalArgumentException(
					"Record generic parameters and arguments must have equal arity.");
		int genericArgumentStructuralComplexity =
				new GenericTraversal().structuralComplexity(arguments);

		List<? extends RecordComponentElement> components =
				declaration.getRecordComponents();
		if (components.size() > limits.maximumCollectionEntryCount())
			throw limit(
					McpSchemaCompilationException.Limit.COLLECTION_ENTRY_COUNT,
					"Record component count exceeds its configured limit.");
		for (RecordComponentElement component : components) {
			if (component.getSimpleName().length()
					> limits.maximumNameLengthInCharacters())
				throw limit(McpSchemaCompilationException.Limit.NAME_LENGTH,
						"Record component name exceeds its configured limit.");
		}
		Set<TypeParameterElement> declaredParameters =
				new LinkedHashSet<>(parameters);
		Set<TypeParameterElement> usedParameters = new LinkedHashSet<>();
		GenericTraversal usageTraversal = new GenericTraversal();
		for (RecordComponentElement component : components)
			usageTraversal.collectReferencedParameters(component.asType(),
					declaredParameters, usedParameters);
		List<TypeMirror> screeningOnlyGenericArguments = new ArrayList<>();
		for (int index = 0; index < parameters.size(); ++index) {
			if (!usedParameters.contains(parameters.get(index)))
				screeningOnlyGenericArguments.add(arguments.get(index));
		}

		List<McpTypedTypeDescriptor.RecordComponent<TypeMirror>> described =
				new ArrayList<>(components.size());
		for (RecordComponentElement component : components) {
			TypeMirror accessorAsMember = types.asMemberOf(type,
					component.getAccessor());
			if (!(accessorAsMember instanceof ExecutableType accessor))
				throw new IllegalArgumentException(
						"A record component accessor must have an executable type.");
			described.add(McpTypedTypeDescriptor.RecordComponent
					.fromNameAndType(component.getSimpleName().toString(),
							accessor.getReturnType()));
		}
		return new McpTypedTypeDescriptor.RecordValue<>(binaryName(declaration),
				described, genericArgumentStructuralComplexity,
				screeningOnlyGenericArguments);
	}

	private List<? extends TypeMirror> typeArguments(DeclaredType type) {
		List<? extends TypeMirror> arguments = requireNonNull(
				type.getTypeArguments());
		if (arguments.size() > limits.maximumCollectionEntryCount())
			throw limit(McpSchemaCompilationException.Limit.COLLECTION_ENTRY_COUNT,
					"Generic argument count exceeds its configured limit.");
		for (TypeMirror argument : arguments)
			requireNonNull(argument);
		return arguments;
	}

	private boolean frameworkType(DeclaredType type) {
		if (assignableTo(type, INTERNAL_JSON_VALUE))
			return true;
		for (String frameworkRootTypeName : frameworkRootTypeNames) {
			if (assignableTo(type, frameworkRootTypeName))
				return true;
		}
		return false;
	}

	private boolean assignableTo(TypeMirror type, String targetName) {
		TypeElement target = elements.getTypeElement(targetName);
		return target != null && types.isAssignable(types.erasure(type),
				types.erasure(target.asType()));
	}

	private final class GenericTraversal {
		private final Set<TypeMirror> activeTypes =
				Collections.newSetFromMap(new IdentityHashMap<>());
		private int visitedNodeCount;

		private boolean containsUnresolved(TypeMirror type) {
			return containsUnresolved(type, 1);
		}

		private boolean containsUnresolved(TypeMirror type, int depth) {
			enter(type, depth);
			try {
				if (type.getKind() == TypeKind.TYPEVAR
						|| type.getKind() == TypeKind.WILDCARD)
					return true;
				if (type instanceof ArrayType array)
					return containsUnresolved(array.getComponentType(), depth + 1);
				if (type instanceof DeclaredType declared) {
					TypeMirror enclosing = requireNonNull(
							declared.getEnclosingType());
					if (enclosing.getKind() != TypeKind.NONE
							&& containsUnresolved(enclosing, depth + 1))
						return true;
					for (TypeMirror argument : typeArguments(declared)) {
						if (containsUnresolved(argument, depth + 1))
							return true;
					}
				}
				return false;
			} finally {
				exit(type);
			}
		}

		private int structuralComplexity(
				List<? extends TypeMirror> argumentTypes) {
			for (TypeMirror argumentType : argumentTypes)
				visitStructure(argumentType, 1);
			return visitedNodeCount;
		}

		private void visitStructure(TypeMirror type, int depth) {
			enter(type, depth);
			try {
				if (type instanceof ArrayType array) {
					visitStructure(array.getComponentType(), depth + 1);
				} else if (type instanceof DeclaredType declared) {
					TypeMirror enclosing = requireNonNull(
							declared.getEnclosingType());
					if (enclosing.getKind() != TypeKind.NONE)
						visitStructure(enclosing, depth + 1);
					for (TypeMirror argument : typeArguments(declared))
						visitStructure(argument, depth + 1);
				}
			} finally {
				exit(type);
			}
		}

		private void collectReferencedParameters(TypeMirror type,
				Set<TypeParameterElement> declaredParameters,
				Set<TypeParameterElement> destination) {
			collectReferencedParameters(type, declaredParameters, destination, 1);
		}

		private void collectReferencedParameters(TypeMirror type,
				Set<TypeParameterElement> declaredParameters,
				Set<TypeParameterElement> destination, int depth) {
			enter(type, depth);
			try {
				if (type instanceof TypeVariable variable) {
					Element parameter = variable.asElement();
					if (parameter instanceof TypeParameterElement typeParameter
							&& declaredParameters.contains(typeParameter))
						destination.add(typeParameter);
					return;
				}
				if (type instanceof ArrayType array) {
					collectReferencedParameters(array.getComponentType(),
							declaredParameters, destination, depth + 1);
					return;
				}
				if (type instanceof DeclaredType declared) {
					TypeMirror enclosing = requireNonNull(
							declared.getEnclosingType());
					if (enclosing.getKind() != TypeKind.NONE)
						collectReferencedParameters(enclosing,
								declaredParameters, destination, depth + 1);
					for (TypeMirror argument : typeArguments(declared))
						collectReferencedParameters(argument,
								declaredParameters, destination, depth + 1);
					return;
				}
				if (type instanceof WildcardType wildcard) {
					TypeMirror lowerBound = wildcard.getSuperBound();
					if (lowerBound != null)
						collectReferencedParameters(lowerBound,
								declaredParameters, destination, depth + 1);
					TypeMirror upperBound = wildcard.getExtendsBound();
					if (upperBound != null)
						collectReferencedParameters(upperBound,
								declaredParameters, destination, depth + 1);
				}
			} finally {
				exit(type);
			}
		}

		private void enter(TypeMirror type, int depth) {
			requireNonNull(type);
			if (depth > limits.maximumSchemaDepth())
				throw limit(McpSchemaCompilationException.Limit.SCHEMA_DEPTH,
						"Generic type structure exceeds its configured depth limit.");
			if (visitedNodeCount >= limits.maximumSchemaNodeCount())
				throw limit(McpSchemaCompilationException.Limit.SCHEMA_NODE_COUNT,
						"Generic type structure exceeds its configured node limit.");
			visitedNodeCount++;
			if (!activeTypes.add(type))
				throw new IllegalArgumentException(
						"Generic type metadata contains an identity cycle.");
		}

		private void exit(TypeMirror type) {
			activeTypes.remove(type);
		}
	}

	private @Nullable McpTypedSchemaScalar scalar(TypeMirror type) {
		McpTypedSchemaScalar primitive = switch (type.getKind()) {
			case BOOLEAN -> McpTypedSchemaScalar.BOOLEAN;
			case BYTE -> McpTypedSchemaScalar.BYTE;
			case SHORT -> McpTypedSchemaScalar.SHORT;
			case INT -> McpTypedSchemaScalar.INT;
			case LONG -> McpTypedSchemaScalar.LONG;
			case FLOAT -> McpTypedSchemaScalar.FLOAT;
			case DOUBLE -> McpTypedSchemaScalar.DOUBLE;
			default -> null;
		};
		if (primitive != null || type.getKind() != TypeKind.DECLARED)
			return primitive;
		if (!(((DeclaredType) type).asElement() instanceof TypeElement declaration))
			return null;
		return switch (declaration.getQualifiedName().toString()) {
			case BOOLEAN -> McpTypedSchemaScalar.BOOLEAN;
			case BYTE -> McpTypedSchemaScalar.BYTE;
			case SHORT -> McpTypedSchemaScalar.SHORT;
			case INTEGER -> McpTypedSchemaScalar.INT;
			case LONG -> McpTypedSchemaScalar.LONG;
			case BIG_INTEGER -> McpTypedSchemaScalar.BIG_INTEGER;
			case FLOAT -> McpTypedSchemaScalar.FLOAT;
			case DOUBLE -> McpTypedSchemaScalar.DOUBLE;
			case BIG_DECIMAL -> McpTypedSchemaScalar.BIG_DECIMAL;
			case STRING -> McpTypedSchemaScalar.STRING;
			default -> null;
		};
	}

	private String binaryName(TypeElement declaration) {
		return elements.getBinaryName(declaration).toString();
	}

	private McpTypedTypeDescriptor<TypeMirror> unsupported(
			McpTypedSchemaException.Reason reason) {
		return new McpTypedTypeDescriptor.Unsupported<>(reason);
	}

	private McpTypedTypeModelLimitException limit(
			McpSchemaCompilationException.Limit limit, String message) {
		return new McpTypedTypeModelLimitException(limit, message);
	}
}
