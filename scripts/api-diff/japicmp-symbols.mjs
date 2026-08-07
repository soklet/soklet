#!/usr/bin/env node

import { readFileSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { TextDecoder } from 'node:util';

const CHANGE_STATUSES = new Set(['NEW', 'REMOVED', 'UNCHANGED', 'MODIFIED']);
const COMPATIBILITY_CHANGE_TYPES = new Set([
  'ANNOTATION_ADDED',
  'ANNOTATION_DEPRECATED_ADDED',
  'ANNOTATION_MODIFIED',
  'ANNOTATION_REMOVED',
  'CLASS_REMOVED',
  'CLASS_NOW_ABSTRACT',
  'CLASS_NOW_NOT_EXTENDABLE',
  'CLASS_NO_LONGER_PUBLIC',
  'CLASS_TYPE_CHANGED',
  'CLASS_NOW_CHECKED_EXCEPTION',
  'CLASS_LESS_ACCESSIBLE',
  'CLASS_GENERIC_TEMPLATE_CHANGED',
  'CLASS_GENERIC_TEMPLATE_GENERICS_CHANGED',
  'SUPERCLASS_REMOVED',
  'SUPERCLASS_ADDED',
  'SUPERCLASS_MODIFIED_INCOMPATIBLE',
  'INTERFACE_ADDED',
  'INTERFACE_REMOVED',
  'METHOD_REMOVED',
  'METHOD_REMOVED_IN_SUPERCLASS',
  'METHOD_LESS_ACCESSIBLE',
  'METHOD_LESS_ACCESSIBLE_THAN_IN_SUPERCLASS',
  'METHOD_IS_STATIC_AND_OVERRIDES_NOT_STATIC',
  'METHOD_RETURN_TYPE_CHANGED',
  'METHOD_RETURN_TYPE_COVARIANT_CHANGED',
  'METHOD_RETURN_TYPE_GENERICS_CHANGED',
  'METHOD_PARAMETER_GENERICS_CHANGED',
  'METHOD_NOW_ABSTRACT',
  'METHOD_NOW_FINAL',
  'METHOD_NOW_STATIC',
  'METHOD_NO_LONGER_STATIC',
  'METHOD_NOW_VARARGS',
  'METHOD_NO_LONGER_VARARGS',
  'METHOD_ADDED_TO_INTERFACE',
  'METHOD_ADDED_TO_PUBLIC_CLASS',
  'METHOD_NOW_THROWS_CHECKED_EXCEPTION',
  'METHOD_NO_LONGER_THROWS_CHECKED_EXCEPTION',
  'METHOD_ABSTRACT_ADDED_TO_CLASS',
  'METHOD_ABSTRACT_ADDED_IN_SUPERCLASS',
  'METHOD_ABSTRACT_ADDED_IN_IMPLEMENTED_INTERFACE',
  'METHOD_DEFAULT_ADDED_IN_IMPLEMENTED_INTERFACE',
  'METHOD_NEW_DEFAULT',
  'METHOD_NEW_STATIC_ADDED_TO_INTERFACE',
  'METHOD_MOVED_TO_SUPERCLASS',
  'METHOD_ABSTRACT_NOW_DEFAULT',
  'METHOD_NON_STATIC_IN_INTERFACE_NOW_STATIC',
  'METHOD_STATIC_IN_INTERFACE_NO_LONGER_STATIC',
  'FIELD_STATIC_AND_OVERRIDES_STATIC',
  'FIELD_LESS_ACCESSIBLE_THAN_IN_SUPERCLASS',
  'FIELD_NOW_FINAL',
  'FIELD_NOW_TRANSIENT',
  'FIELD_NOW_VOLATILE',
  'FIELD_NOW_STATIC',
  'FIELD_NO_LONGER_TRANSIENT',
  'FIELD_NO_LONGER_VOLATILE',
  'FIELD_NO_LONGER_STATIC',
  'FIELD_TYPE_CHANGED',
  'FIELD_REMOVED',
  'FIELD_REMOVED_IN_SUPERCLASS',
  'FIELD_LESS_ACCESSIBLE',
  'FIELD_GENERICS_CHANGED',
  'CONSTRUCTOR_REMOVED',
  'CONSTRUCTOR_LESS_ACCESSIBLE',
]);
const DIRECT_SYMBOL_REMOVALS = Object.freeze({
  class: Object.freeze({ type: 'CLASS_REMOVED', site: 'class' }),
  constructor: Object.freeze({ type: 'CONSTRUCTOR_REMOVED', site: 'constructor' }),
  field: Object.freeze({ type: 'FIELD_REMOVED', site: 'field' }),
  method: Object.freeze({ type: 'METHOD_REMOVED', site: 'method' }),
});
const GENERIC_WILDCARDS = new Set(['NONE', 'EXTENDS', 'SUPER', 'UNBOUNDED']);
const SERIALIZATION_STATUSES = new Set([
  'NOT_SERIALIZABLE',
  'SERIALIZABLE_COMPATIBLE',
  'SERIALIZABLE_INCOMPATIBLE_SERIALVERSIONUID_MODIFIED',
  'SERIALIZABLE_INCOMPATIBLE_SERIALVERSIONUID_REMOVED_AND_NOT_MATCHES_NEW_DEFAULT',
  'SERIALIZABLE_INCOMPATIBLE_SERIALVERSIONUID_ADDED_AND_NOT_MATCHES_OLD_DEFAULT',
  'SERIALIZABLE_INCOMPATIBLE_CLASS_TYPE_MODIFIED',
  'SERIALIZABLE_INCOMPATIBLE_CHANGED_FROM_SERIALIZABLE_TO_EXTERNALIZABLE',
  'SERIALIZABLE_INCOMPATIBLE_CHANGED_FROM_EXTERNALIZABLE_TO_SERIALIZABLE',
  'SERIALIZABLE_INCOMPATIBLE_SERIALIZABLE_REMOVED',
  'SERIALIZABLE_INCOMPATIBLE_EXTERNALIZABLE_REMOVED',
  'SERIALIZABLE_INCOMPATIBLE_FIELD_REMOVED',
  'SERIALIZABLE_INCOMPATIBLE_FIELD_CHANGED_FROM_NONSTATIC_TO_STATIC',
  'SERIALIZABLE_INCOMPATIBLE_FIELD_CHANGED_FROM_NONTRANSIENT_TO_TRANSIENT',
  'SERIALIZABLE_INCOMPATIBLE_FIELD_TYPE_MODIFIED',
  'SERIALIZABLE_INCOMPATIBLE_BUT_SUID_EQUAL',
  'SERIALIZABLE_INCOMPATIBLE_CLASS_REMOVED',
  'SERIALIZABLE_INCOMPATIBLE_DEFAULT_SERIALVERSIONUID_CHANGED',
  'SERIALIZABLE_INCOMPATIBLE_SUPERCLASS_MODIFIED',
]);

class ApiDiffError extends Error {}

function problem(message) {
  throw new ApiDiffError(message);
}

function bytewiseCompare(left, right) {
  return Buffer.compare(Buffer.from(left, 'utf8'), Buffer.from(right, 'utf8'));
}

function decodeXmlEntity(entity, location) {
  const named = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };
  if (Object.hasOwn(named, entity)) return named[entity];
  let codePoint;
  if (/^#[0-9]+$/.test(entity)) codePoint = Number(entity.slice(1));
  else if (/^#x[0-9A-Fa-f]+$/.test(entity)) codePoint = Number.parseInt(entity.slice(2), 16);
  else problem(`Unsupported XML entity &${entity}; at ${location}`);
  if (!Number.isSafeInteger(codePoint) || !isXmlCodePoint(codePoint)) {
    problem(`Invalid XML character reference &${entity}; at ${location}`);
  }
  return String.fromCodePoint(codePoint);
}

function isXmlCodePoint(codePoint) {
  return codePoint === 0x9 || codePoint === 0xa || codePoint === 0xd ||
    (codePoint >= 0x20 && codePoint <= 0xd7ff) ||
    (codePoint >= 0xe000 && codePoint <= 0xfffd) ||
    (codePoint >= 0x10000 && codePoint <= 0x10ffff);
}

function isXmlWhitespace(value) {
  return /^[ \t\r\n]*$/.test(value);
}

function decodeAttribute(value, location) {
  if (value.includes('<')) problem(`Raw '<' in XML attribute at ${location}`);
  let decoded = '';
  for (let index = 0; index < value.length;) {
    if (value[index] !== '&') {
      const codePoint = value.codePointAt(index);
      if (!isXmlCodePoint(codePoint)) problem(`Invalid XML character at ${location}`);
      decoded += String.fromCodePoint(codePoint);
      index += codePoint > 0xffff ? 2 : 1;
      continue;
    }
    const end = value.indexOf(';', index + 1);
    if (end === -1) problem(`Unterminated XML entity at ${location}`);
    decoded += decodeXmlEntity(value.slice(index + 1, end), location);
    index = end + 1;
  }
  return decoded;
}

function parseXml(text) {
  const declaration = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>';
  if (!text.startsWith(declaration)) {
    problem('Expected the exact japicmp XML declaration');
  }
  let offset = declaration.length;
  const stack = [];
  let root;

  while (offset < text.length) {
    const open = text.indexOf('<', offset);
    if (open === -1) {
      if (!isXmlWhitespace(text.slice(offset))) problem('Non-whitespace text after the XML root');
      break;
    }
    if (!isXmlWhitespace(text.slice(offset, open))) {
      problem(`Unexpected XML text at byte ${Buffer.byteLength(text.slice(0, offset), 'utf8')}`);
    }
    if (text.startsWith('<!--', open) || text.startsWith('<![CDATA[', open) ||
        text.startsWith('<!DOCTYPE', open) || text.startsWith('<!ENTITY', open) ||
        text.startsWith('<?', open)) {
      problem(`Unsupported XML construct at character ${open}`);
    }
    let end = open + 1;
    let quote;
    while (end < text.length) {
      const character = text[end];
      if (quote !== undefined) {
        if (character === quote) quote = undefined;
      } else if (character === '"' || character === "'") {
        quote = character;
      } else if (character === '>') {
        break;
      }
      end += 1;
    }
    if (end >= text.length || quote !== undefined) problem(`Unterminated XML tag at character ${open}`);
    const tag = text.slice(open + 1, end);
    if (tag.startsWith('/')) {
      const closingName = tag.slice(1).trim();
      if (!/^[A-Za-z_][A-Za-z0-9_.:-]*$/.test(closingName) || tag.slice(1) !== closingName) {
        problem(`Malformed closing tag at character ${open}`);
      }
      const current = stack.pop();
      if (current === undefined || current.name !== closingName) {
        problem(`Mismatched closing tag </${closingName}> at character ${open}`);
      }
    } else {
      const selfClosing = /\/[ \t\r\n]*$/.test(tag);
      const contents = selfClosing ? tag.replace(/\/[ \t\r\n]*$/, '') : tag;
      let cursor = 0;
      const nameMatch = /^[A-Za-z_][A-Za-z0-9_.:-]*/.exec(contents);
      if (nameMatch === null) problem(`Malformed opening tag at character ${open}`);
      const name = nameMatch[0];
      cursor = name.length;
      const attributes = Object.create(null);
      while (cursor < contents.length) {
        const whitespace = /^[ \t\r\n]+/.exec(contents.slice(cursor));
        if (whitespace === null) problem(`Missing whitespace before an attribute on <${name}>`);
        cursor += whitespace[0].length;
        if (cursor === contents.length) break;
        const attributeMatch = /^[A-Za-z_][A-Za-z0-9_.:-]*/.exec(contents.slice(cursor));
        if (attributeMatch === null) problem(`Malformed attribute on <${name}>`);
        const attributeName = attributeMatch[0];
        cursor += attributeName.length;
        const equalsMatch = /^[ \t\r\n]*=[ \t\r\n]*/.exec(contents.slice(cursor));
        if (equalsMatch === null) problem(`Missing '=' after ${attributeName} on <${name}>`);
        cursor += equalsMatch[0].length;
        const attributeQuote = contents[cursor];
        if (attributeQuote !== '"' && attributeQuote !== "'") {
          problem(`Unquoted attribute ${attributeName} on <${name}>`);
        }
        cursor += 1;
        const valueEnd = contents.indexOf(attributeQuote, cursor);
        if (valueEnd === -1) problem(`Unterminated attribute ${attributeName} on <${name}>`);
        if (Object.hasOwn(attributes, attributeName)) {
          problem(`Duplicate attribute ${attributeName} on <${name}>`);
        }
        attributes[attributeName] = decodeAttribute(
          contents.slice(cursor, valueEnd),
          `<${name}>/@${attributeName}`,
        );
        cursor = valueEnd + 1;
      }
      const node = { name, attributes, children: [] };
      if (stack.length === 0) {
        if (root !== undefined) problem('Multiple XML root elements');
        root = node;
      } else {
        stack.at(-1).children.push(node);
      }
      if (!selfClosing) stack.push(node);
    }
    offset = end + 1;
  }
  if (stack.length !== 0) problem(`Unclosed XML element <${stack.at(-1).name}>`);
  if (root === undefined) problem('Missing XML root element');
  return root;
}

function expectAttributes(node, required, optional = []) {
  const allowed = new Set([...required, ...optional]);
  for (const name of Object.keys(node.attributes)) {
    if (!allowed.has(name)) problem(`Unknown attribute ${name} on <${node.name}>`);
  }
  for (const name of required) {
    if (!Object.hasOwn(node.attributes, name)) problem(`Missing attribute ${name} on <${node.name}>`);
  }
}

function expectBoolean(node, name) {
  if (node.attributes[name] !== 'true' && node.attributes[name] !== 'false') {
    problem(`Attribute ${name} on <${node.name}> is not an XML boolean`);
  }
}

function expectStatus(node) {
  if (!CHANGE_STATUSES.has(node.attributes.changeStatus)) {
    problem(`Unknown or missing changeStatus on <${node.name}>`);
  }
}

function validateOrderedChildren(node, order, validators, repeatable = []) {
  const repeats = new Set(repeatable);
  const seen = new Set();
  let previous = -1;
  for (const child of node.children) {
    const position = order.indexOf(child.name);
    if (position === -1) problem(`Unknown child <${child.name}> in <${node.name}>`);
    if (position < previous) problem(`Out-of-order child <${child.name}> in <${node.name}>`);
    if (seen.has(child.name) && !repeats.has(child.name)) {
      problem(`Duplicate child <${child.name}> in <${node.name}>`);
    }
    seen.add(child.name);
    previous = position;
    validators[child.name](child);
  }
}

function validateContainer(node, childName, validator) {
  expectAttributes(node, []);
  for (const child of node.children) {
    if (child.name !== childName) problem(`Unknown child <${child.name}> in <${node.name}>`);
    validator(child);
  }
}

function validateCompatibilityChange(node) {
  expectAttributes(node, ['binaryCompatible', 'sourceCompatible', 'type']);
  expectBoolean(node, 'binaryCompatible');
  expectBoolean(node, 'sourceCompatible');
  if (!COMPATIBILITY_CHANGE_TYPES.has(node.attributes.type)) {
    problem(`Unknown japicmp 0.26.1 compatibility change: ${node.attributes.type}`);
  }
  if (node.children.length !== 0) problem('<compatibilityChange> must be empty');
}

const validateCompatibilityChanges = (node) =>
  validateContainer(node, 'compatibilityChange', validateCompatibilityChange);

function validateAnnotationValue(node) {
  expectAttributes(node, [], ['fullyQualifiedName', 'name', 'type', 'value']);
  validateOrderedChildren(node, ['values'], {
    values: (child) => validateContainer(child, 'value', validateAnnotationValue),
  });
}

function validateAnnotationElement(node) {
  expectAttributes(node, ['binaryCompatible', 'sourceCompatible', 'changeStatus', 'name']);
  expectBoolean(node, 'binaryCompatible');
  expectBoolean(node, 'sourceCompatible');
  expectStatus(node);
  validateOrderedChildren(node, ['compatibilityChanges', 'newElementValues', 'oldElementValues'], {
    compatibilityChanges: validateCompatibilityChanges,
    newElementValues: (child) => validateContainer(child, 'newElementValue', validateAnnotationValue),
    oldElementValues: (child) => validateContainer(child, 'oldElementValue', validateAnnotationValue),
  });
}

function validateAnnotation(node) {
  expectAttributes(node, [
    'binaryCompatible', 'sourceCompatible', 'changeStatus', 'fullyQualifiedName',
  ]);
  expectBoolean(node, 'binaryCompatible');
  expectBoolean(node, 'sourceCompatible');
  expectStatus(node);
  validateOrderedChildren(node, ['compatibilityChanges', 'elements'], {
    compatibilityChanges: validateCompatibilityChanges,
    elements: (child) => validateContainer(child, 'element', validateAnnotationElement),
  });
}

function validateAttribute(node) {
  expectAttributes(node, ['changeStatus'], ['newValue', 'oldValue']);
  expectStatus(node);
  if (node.children.length !== 0) problem('<attribute> must be empty');
}

function validateClassFileFormatVersion(node) {
  expectAttributes(node, [
    'changeStatus', 'majorVersionNew', 'majorVersionOld', 'minorVersionNew', 'minorVersionOld',
  ]);
  expectStatus(node);
  for (const name of ['majorVersionNew', 'majorVersionOld', 'minorVersionNew', 'minorVersionOld']) {
    if (!/^-?[0-9]+$/.test(node.attributes[name])) problem(`Invalid ${name} on <${node.name}>`);
  }
  if (node.children.length !== 0) problem('<classFileFormatVersion> must be empty');
}

function validateClassType(node) {
  expectAttributes(node, ['changeStatus'], ['newType', 'oldType']);
  expectStatus(node);
  if (node.children.length !== 0) problem('<classType> must be empty');
}

function validateException(node) {
  expectAttributes(node, ['changeStatus', 'name']);
  expectStatus(node);
  if (node.children.length !== 0) problem('<exception> must be empty');
}

function validateGenericType(node) {
  expectAttributes(node, ['type'], ['genericWildCard']);
  if (Object.hasOwn(node.attributes, 'genericWildCard') &&
      !GENERIC_WILDCARDS.has(node.attributes.genericWildCard)) {
    problem(`Unknown genericWildCard on <${node.name}>`);
  }
  validateOrderedChildren(node, ['genericTypes'], {
    genericTypes: (child) => validateContainer(child, 'genericType', validateGenericType),
  });
}

function validateGenericTemplate(node) {
  expectAttributes(node, [
    'binaryCompatible', 'sourceCompatible', 'changeStatus', 'name',
  ], ['newType', 'oldType']);
  expectBoolean(node, 'binaryCompatible');
  expectBoolean(node, 'sourceCompatible');
  expectStatus(node);
  validateOrderedChildren(node, [
    'compatibilityChanges', 'newGenericTypes', 'newInterfaceTypes',
    'oldGenericTypes', 'oldInterfaceTypes',
  ], {
    compatibilityChanges: validateCompatibilityChanges,
    newGenericTypes: (child) => validateContainer(child, 'newGenericType', validateGenericType),
    newInterfaceTypes: (child) => validateContainer(child, 'newInterfaceType', validateGenericType),
    oldGenericTypes: (child) => validateContainer(child, 'oldGenericType', validateGenericType),
    oldInterfaceTypes: (child) => validateContainer(child, 'oldInterfaceType', validateGenericType),
  });
}

function validateModifier(node) {
  expectAttributes(node, ['changeStatus'], ['newValue', 'oldValue']);
  expectStatus(node);
  if (node.children.length !== 0) problem('<modifier> must be empty');
}

function validateParameter(node) {
  expectAttributes(node, [
    'binaryCompatible', 'sourceCompatible', 'changeStatus', 'type',
  ], ['templateName']);
  expectBoolean(node, 'binaryCompatible');
  expectBoolean(node, 'sourceCompatible');
  expectStatus(node);
  validateOrderedChildren(node, ['compatibilityChanges', 'newGenericTypes', 'oldGenericTypes'], {
    compatibilityChanges: validateCompatibilityChanges,
    newGenericTypes: (child) => validateContainer(child, 'newGenericType', validateGenericType),
    oldGenericTypes: (child) => validateContainer(child, 'oldGenericType', validateGenericType),
  });
}

function validateReturnType(node) {
  expectAttributes(node, [
    'binaryCompatible', 'sourceCompatible', 'changeStatus', 'newValue', 'oldValue',
  ]);
  expectBoolean(node, 'binaryCompatible');
  expectBoolean(node, 'sourceCompatible');
  expectStatus(node);
  validateOrderedChildren(node, ['compatibilityChanges', 'newGenericTypes', 'oldGenericTypes'], {
    compatibilityChanges: validateCompatibilityChanges,
    newGenericTypes: (child) => validateContainer(child, 'newGenericType', validateGenericType),
    oldGenericTypes: (child) => validateContainer(child, 'oldGenericType', validateGenericType),
  });
}

function validateBehavior(node, method) {
  expectAttributes(node, [
    'binaryCompatible', 'sourceCompatible', 'changeStatus', 'name',
  ], ['newLineNumber', 'oldLineNumber']);
  expectBoolean(node, 'binaryCompatible');
  expectBoolean(node, 'sourceCompatible');
  expectStatus(node);
  const order = [
    'annotations', 'attributes', 'compatibilityChanges', 'exceptions',
    'genericTemplates', 'modifiers', 'parameters',
  ];
  if (method) order.push('returnType');
  validateOrderedChildren(node, order, {
    annotations: (child) => validateContainer(child, 'annotation', validateAnnotation),
    attributes: (child) => validateContainer(child, 'attribute', validateAttribute),
    compatibilityChanges: validateCompatibilityChanges,
    exceptions: (child) => validateContainer(child, 'exception', validateException),
    genericTemplates: (child) => validateContainer(child, 'genericTemplate', validateGenericTemplate),
    modifiers: (child) => validateContainer(child, 'modifier', validateModifier),
    parameters: (child) => validateContainer(child, 'parameter', validateParameter),
    returnType: validateReturnType,
  });
  if (method && !node.children.some((child) => child.name === 'returnType')) {
    problem(`<method name="${node.attributes.name}"> has no returnType`);
  }
}

function validateType(node) {
  expectAttributes(node, ['changeStatus', 'newValue', 'oldValue']);
  expectStatus(node);
  if (node.children.length !== 0) problem('<type> must be empty');
}

function validateField(node) {
  expectAttributes(node, [
    'binaryCompatible', 'sourceCompatible', 'changeStatus', 'name',
  ]);
  expectBoolean(node, 'binaryCompatible');
  expectBoolean(node, 'sourceCompatible');
  expectStatus(node);
  validateOrderedChildren(node, [
    'annotations', 'attributes', 'compatibilityChanges', 'modifiers',
    'newGenericTypes', 'oldGenericTypes', 'type',
  ], {
    annotations: (child) => validateContainer(child, 'annotation', validateAnnotation),
    attributes: (child) => validateContainer(child, 'attribute', validateAttribute),
    compatibilityChanges: validateCompatibilityChanges,
    modifiers: (child) => validateContainer(child, 'modifier', validateModifier),
    newGenericTypes: (child) => validateContainer(child, 'newGenericType', validateGenericType),
    oldGenericTypes: (child) => validateContainer(child, 'oldGenericType', validateGenericType),
    type: validateType,
  });
}

function validateInterface(node) {
  expectAttributes(node, [
    'binaryCompatible', 'sourceCompatible', 'changeStatus', 'fullyQualifiedName',
  ]);
  expectBoolean(node, 'binaryCompatible');
  expectBoolean(node, 'sourceCompatible');
  expectStatus(node);
  validateOrderedChildren(node, ['compatibilityChanges'], {
    compatibilityChanges: validateCompatibilityChanges,
  });
}

function validateSerialVersionUid(node) {
  expectAttributes(node, ['serializableNew', 'serializableOld'], [
    'serialVersionUidDefaultNew', 'serialVersionUidDefaultOld',
    'serialVersionUidInClassNew', 'serialVersionUidInClassOld',
  ]);
  expectBoolean(node, 'serializableNew');
  expectBoolean(node, 'serializableOld');
  if (node.children.length !== 0) problem('<serialVersionUid> must be empty');
}

function validateSuperclass(node) {
  expectAttributes(node, [
    'binaryCompatible', 'sourceCompatible', 'changeStatus',
    'superclassNew', 'superclassOld',
  ]);
  expectBoolean(node, 'binaryCompatible');
  expectBoolean(node, 'sourceCompatible');
  expectStatus(node);
  validateOrderedChildren(node, ['compatibilityChanges'], {
    compatibilityChanges: validateCompatibilityChanges,
  });
}

function validateClass(node) {
  expectAttributes(node, [
    'binaryCompatible', 'sourceCompatible', 'changeStatus', 'fullyQualifiedName',
  ], ['javaObjectSerializationCompatible', 'javaObjectSerializationCompatibleAsString']);
  expectBoolean(node, 'binaryCompatible');
  expectBoolean(node, 'sourceCompatible');
  expectStatus(node);
  if (Object.hasOwn(node.attributes, 'javaObjectSerializationCompatible') &&
      !SERIALIZATION_STATUSES.has(node.attributes.javaObjectSerializationCompatible)) {
    problem(`Unknown javaObjectSerializationCompatible value on ${node.attributes.fullyQualifiedName}`);
  }
  validateOrderedChildren(node, [
    'annotations', 'attributes', 'classFileFormatVersion', 'classType',
    'compatibilityChanges', 'constructors', 'fields', 'genericTemplates',
    'interfaces', 'methods', 'modifiers', 'serialVersionUid', 'superclass',
  ], {
    annotations: (child) => validateContainer(child, 'annotation', validateAnnotation),
    attributes: (child) => validateContainer(child, 'attribute', validateAttribute),
    classFileFormatVersion: validateClassFileFormatVersion,
    classType: validateClassType,
    compatibilityChanges: validateCompatibilityChanges,
    constructors: (child) => validateContainer(
      child,
      'constructor',
      (constructor) => validateBehavior(constructor, false),
    ),
    fields: (child) => validateContainer(child, 'field', validateField),
    genericTemplates: (child) => validateContainer(child, 'genericTemplate', validateGenericTemplate),
    interfaces: (child) => validateContainer(child, 'interface', validateInterface),
    methods: (child) => validateContainer(child, 'method', (method) => validateBehavior(method, true)),
    modifiers: (child) => validateContainer(child, 'modifier', validateModifier),
    serialVersionUid: validateSerialVersionUid,
    superclass: validateSuperclass,
  });
}

function validateRoot(root, expectedOnlyModifications) {
  if (root.name !== 'japicmp') problem(`Expected <japicmp>, found <${root.name}>`);
  expectAttributes(root, [
    'xmlns:xsi', 'accessModifier', 'creationTimestamp', 'ignoreMissingClasses',
    'newJar', 'newVersion', 'oldJar', 'oldVersion',
    'onlyBinaryIncompatibleModifications', 'onlyModifications',
    'packagesExclude', 'packagesInclude', 'semanticVersioning', 'title',
    'xsi:noNamespaceSchemaLocation',
  ], ['ignoreMissingClassesByRegularExpressions']);
  if (root.attributes['xmlns:xsi'] !== 'http://www.w3.org/2001/XMLSchema-instance' ||
      root.attributes['xsi:noNamespaceSchemaLocation'] !== 'japicmp.xsd') {
    problem('Unexpected japicmp XML namespace or schema location');
  }
  for (const name of [
    'ignoreMissingClasses', 'onlyBinaryIncompatibleModifications', 'onlyModifications',
  ]) expectBoolean(root, name);
  if (root.attributes.accessModifier !== 'PROTECTED' ||
      root.attributes.ignoreMissingClasses !== 'false' ||
      (Object.hasOwn(root.attributes,
        'ignoreMissingClassesByRegularExpressions') &&
        root.attributes.ignoreMissingClassesByRegularExpressions !== '') ||
      root.attributes.onlyBinaryIncompatibleModifications !== 'false' ||
      root.attributes.onlyModifications !== String(expectedOnlyModifications) ||
      root.attributes.packagesExclude !== 'n.a.' ||
      root.attributes.packagesInclude !== 'all') {
    const reportKind = expectedOnlyModifications ? 'modified-only' : 'full';
    problem(`japicmp report does not satisfy the complete public/protected ${reportKind} comparison contract`);
  }
  if (root.attributes.creationTimestamp.length === 0 ||
      root.attributes.newJar.length === 0 || root.attributes.oldJar.length === 0 ||
      root.attributes.newVersion.length === 0 || root.attributes.oldVersion.length === 0 ||
      root.attributes.semanticVersioning.length === 0 || root.attributes.title.length === 0) {
    problem('japicmp report has an empty required metadata value');
  }
  if (/[\\/]/.test(root.attributes.newJar) || /[\\/]/.test(root.attributes.oldJar)) {
    problem('japicmp report leaks a directory path in newJar or oldJar');
  }
  validateOrderedChildren(root, ['classes'], {
    classes: (child) => validateContainer(child, 'class', validateClass),
  });
  if (root.children.length !== 1 || root.children[0].name !== 'classes') {
    problem('japicmp report must contain exactly one <classes> container');
  }
}

const REPORT_PAIR_METADATA_ATTRIBUTES = Object.freeze([
  'oldJar', 'oldVersion', 'newJar', 'newVersion',
]);

export function verifyJapicmpReportPair(
  modifiedOnlyXmlText,
  fullXmlText,
  expectedOldVersion,
  expectedOldJar,
) {
  if (typeof expectedOldVersion !== 'string' || expectedOldVersion.length === 0 ||
      typeof expectedOldJar !== 'string' || expectedOldJar.length === 0) {
    problem('Expected japicmp baseline version and JAR must be nonempty strings');
  }

  const modifiedOnlyRoot = parseXml(modifiedOnlyXmlText);
  const fullRoot = parseXml(fullXmlText);
  validateRoot(modifiedOnlyRoot, true);
  validateRoot(fullRoot, false);

  const mismatchedAttributes = REPORT_PAIR_METADATA_ATTRIBUTES.filter((name) =>
    modifiedOnlyRoot.attributes[name] !== fullRoot.attributes[name]);
  if (mismatchedAttributes.length !== 0) {
    problem(`japicmp report pair metadata differs for: ${mismatchedAttributes.join(', ')}`);
  }

  if (modifiedOnlyRoot.attributes.oldVersion !== expectedOldVersion ||
      modifiedOnlyRoot.attributes.oldJar !== expectedOldJar) {
    problem(
      `japicmp report pair baseline must be oldVersion=${JSON.stringify(expectedOldVersion)} ` +
      `and oldJar=${JSON.stringify(expectedOldJar)}; found ` +
      `oldVersion=${JSON.stringify(modifiedOnlyRoot.attributes.oldVersion)} and ` +
      `oldJar=${JSON.stringify(modifiedOnlyRoot.attributes.oldJar)}`,
    );
  }
}

function child(node, name) {
  return node.children.find((candidate) => candidate.name === name);
}

function children(node, containerName) {
  return child(node, containerName)?.children ?? [];
}

function binaryName(javaName, location) {
  if (!/^(?:[A-Za-z_$][A-Za-z0-9_$]*\.)*[A-Za-z_$][A-Za-z0-9_$]*$/.test(javaName)) {
    problem(`Cannot derive a JVM binary name from ${JSON.stringify(javaName)} at ${location}`);
  }
  return javaName.replaceAll('.', '/');
}

function descriptor(javaType, location, allowVoid = false) {
  let type = javaType;
  let dimensions = 0;
  while (type.endsWith('[]')) {
    dimensions += 1;
    type = type.slice(0, -2);
  }
  const primitives = {
    boolean: 'Z', byte: 'B', char: 'C', short: 'S', int: 'I',
    long: 'J', float: 'F', double: 'D', void: 'V',
  };
  let base = primitives[type];
  if (base === undefined) base = `L${binaryName(type, location)};`;
  if (base === 'V' && (!allowVoid || dimensions !== 0)) problem(`Invalid void type at ${location}`);
  return '['.repeat(dimensions) + base;
}

function selectedValue(status, oldValue, newValue, location) {
  const preferred = status === 'NEW' ? newValue : oldValue;
  const fallback = status === 'NEW' ? oldValue : newValue;
  if (preferred !== undefined && preferred !== 'n.a.') return preferred;
  if (fallback !== undefined && fallback !== 'n.a.') return fallback;
  problem(`No usable type value at ${location}`);
}

function directIncompatibilities(node, site) {
  const container = child(node, 'compatibilityChanges');
  if (container === undefined) return [];
  return container.children
    .filter((change) => change.attributes.binaryCompatible === 'false' ||
      change.attributes.sourceCompatible === 'false')
    .map((change) => ({
      type: change.attributes.type,
      site,
      binaryCompatible: change.attributes.binaryCompatible === 'true',
      sourceCompatible: change.attributes.sourceCompatible === 'true',
    }));
}

function directSymbolChanges(node, kind) {
  const removal = DIRECT_SYMBOL_REMOVALS[kind];
  if (removal === undefined) problem(`Unknown exported symbol kind ${kind}`);
  const changes = directIncompatibilities(node, removal.site);
  if (node.attributes.changeStatus === 'REMOVED' &&
      !changes.some((change) => change.type === removal.type && change.site === removal.site)) {
    changes.push({
      type: removal.type,
      site: removal.site,
      binaryCompatible: node.attributes.binaryCompatible === 'true',
      sourceCompatible: node.attributes.sourceCompatible === 'true',
    });
  }
  return changes;
}

function methodIdentity(owner, method) {
  const parameterDescriptors = children(method, 'parameters')
    .map((parameter, index) => descriptor(
      parameter.attributes.type,
      `${owner}#${method.attributes.name} parameter ${index}`,
    ))
    .join('');
  const returnType = child(method, 'returnType');
  const oldType = returnType.attributes.oldValue;
  const newType = returnType.attributes.newValue;
  const selected = selectedValue(
    method.attributes.changeStatus,
    oldType,
    newType,
    `${owner}#${method.attributes.name} return type`,
  );
  const oldDescriptor = `(${parameterDescriptors})${descriptor(selected, `${owner} method return`, true)}`;
  let newId;
  if (oldType !== 'n.a.' && newType !== 'n.a.' && oldType !== newType) {
    newId = `M:${owner}#${method.attributes.name}(${parameterDescriptors})${descriptor(newType, `${owner} new method return`, true)}`;
  }
  return { id: `M:${owner}#${method.attributes.name}${oldDescriptor}`, newId };
}

function constructorIdentity(owner, constructor) {
  const parameters = children(constructor, 'parameters')
    .map((parameter, index) => descriptor(
      parameter.attributes.type,
      `${owner} constructor parameter ${index}`,
    ))
    .join('');
  return { id: `M:${owner}#<init>(${parameters})V` };
}

function fieldIdentity(owner, field) {
  const type = child(field, 'type');
  if (type === undefined) problem(`Field ${owner}#${field.attributes.name} has no <type>`);
  const selected = selectedValue(
    field.attributes.changeStatus,
    type.attributes.oldValue,
    type.attributes.newValue,
    `${owner}#${field.attributes.name} field type`,
  );
  const id = `F:${owner}#${field.attributes.name}:${descriptor(selected, `${owner} field type`)}`;
  let newId;
  if (type.attributes.oldValue !== 'n.a.' && type.attributes.newValue !== 'n.a.' &&
      type.attributes.oldValue !== type.attributes.newValue) {
    newId = `F:${owner}#${field.attributes.name}:${descriptor(type.attributes.newValue, `${owner} new field type`)}`;
  }
  return { id, newId };
}

function annotationChanges(node, sitePrefix) {
  const changes = [];
  for (const annotation of children(node, 'annotations')) {
    const annotationName = binaryName(
      annotation.attributes.fullyQualifiedName,
      `${sitePrefix} annotation`,
    );
    const annotationSite = `${sitePrefix}/annotation:L${annotationName};`;
    changes.push(...directIncompatibilities(annotation, annotationSite));
    for (const element of children(annotation, 'elements')) {
      changes.push(...directIncompatibilities(
        element,
        `${annotationSite}/element:${element.attributes.name}`,
      ));
    }
  }
  return changes;
}

function genericTemplateChanges(node, sitePrefix) {
  const changes = [];
  for (const template of children(node, 'genericTemplates')) {
    changes.push(...directIncompatibilities(
      template,
      `${sitePrefix}/generic-template:${template.attributes.name}`,
    ));
  }
  return changes;
}

function behaviorChanges(node, sitePrefix, kind) {
  const changes = [
    ...directSymbolChanges(node, kind),
    ...annotationChanges(node, sitePrefix),
    ...genericTemplateChanges(node, sitePrefix),
  ];
  children(node, 'parameters').forEach((parameter, index) => {
    changes.push(...directIncompatibilities(parameter, `${sitePrefix}/parameter:${index}`));
  });
  const returnType = child(node, 'returnType');
  if (returnType !== undefined) {
    changes.push(...directIncompatibilities(returnType, `${sitePrefix}/return`));
  }
  return changes;
}

function canonicalChange(change) {
  return JSON.stringify(change);
}

function addRecord(records, identity, kind, changes) {
  if (changes.length === 0) return;
  if (records.has(identity.id)) problem(`Ambiguous duplicate symbol identity ${identity.id}`);
  const record = { id: identity.id, newId: identity.newId, kind, changes: [] };
  records.set(identity.id, record);
  record.changes.push(...changes);
}

function collectRecords(root) {
  const records = new Map();
  for (const classNode of root.children[0].children) {
    const owner = binaryName(classNode.attributes.fullyQualifiedName, '<class>');
    const classIdentity = { id: `C:${owner}` };
    const classChanges = [
      ...directSymbolChanges(classNode, 'class'),
      ...annotationChanges(classNode, 'class'),
      ...genericTemplateChanges(classNode, 'class'),
    ];
    for (const interfaceNode of children(classNode, 'interfaces')) {
      classChanges.push(...directIncompatibilities(
        interfaceNode,
        `class/interface:${binaryName(interfaceNode.attributes.fullyQualifiedName, `${owner} interface`)}`,
      ));
    }
    const superclass = child(classNode, 'superclass');
    if (superclass !== undefined) {
      classChanges.push(...directIncompatibilities(
        superclass,
        `class/superclass:${superclass.attributes.superclassOld}->${superclass.attributes.superclassNew}`,
      ));
    }
    addRecord(records, classIdentity, 'class', classChanges);

    for (const constructor of children(classNode, 'constructors')) {
      const identity = constructorIdentity(owner, constructor);
      addRecord(records, identity, 'constructor', behaviorChanges(constructor, 'constructor', 'constructor'));
    }
    for (const field of children(classNode, 'fields')) {
      const identity = fieldIdentity(owner, field);
      const changes = [
        ...directSymbolChanges(field, 'field'),
        ...annotationChanges(field, 'field'),
      ];
      addRecord(records, identity, 'field', changes);
    }
    for (const method of children(classNode, 'methods')) {
      const identity = methodIdentity(owner, method);
      addRecord(records, identity, 'method', behaviorChanges(method, 'method', 'method'));
    }
  }
  return records;
}

function canonicalizeRecords(records) {
  const lines = [];
  for (const record of records.values()) {
    const changeLines = record.changes.map(canonicalChange).sort(bytewiseCompare);
    for (let index = 1; index < changeLines.length; index += 1) {
      if (changeLines[index] === changeLines[index - 1]) {
        problem(`Duplicate removal/incompatibility on ${record.id}: ${changeLines[index]}`);
      }
    }
    const changes = changeLines.map((line) => JSON.parse(line));
    const output = record.newId === undefined
      ? { id: record.id, kind: record.kind, changes }
      : { id: record.id, newId: record.newId, kind: record.kind, changes };
    lines.push(JSON.stringify(output));
  }
  lines.sort((left, right) => {
    const leftId = JSON.parse(left).id;
    const rightId = JSON.parse(right).id;
    return bytewiseCompare(leftId, rightId);
  });
  return lines.length === 0 ? '' : `${lines.join('\n')}\n`;
}

const TYPE_NAME = /^(?:[A-Za-z_$][A-Za-z0-9_$]*\.)*[A-Za-z_$][A-Za-z0-9_$]*$/;

function reviewedTypeNames(text, location) {
  if (text.includes('\r')) problem(`${location} must use LF line endings`);
  if (text.length !== 0 && !text.endsWith('\n')) {
    problem(`${location} must end with LF`);
  }

  const names = [];
  const seen = new Set();
  for (const [index, line] of text.split('\n').entries()) {
    if (line.length === 0 || line.startsWith('#')) continue;
    if (line !== line.trim()) {
      problem(`${location} line ${index + 1} has surrounding whitespace`);
    }
    if (!TYPE_NAME.test(line)) {
      problem(`${location} line ${index + 1} is not a fully qualified binary type name`);
    }
    if (seen.has(line)) problem(`${location} contains duplicate type ${line}`);
    seen.add(line);
    names.push(line);
  }

  const sorted = [...names].sort(bytewiseCompare);
  if (names.some((name, index) => name !== sorted[index])) {
    problem(`${location} is not in canonical bytewise type-name order`);
  }
  return names;
}

function currentAttribute(node, name, location, allowNotApplicable = false) {
  if (!Object.hasOwn(node.attributes, name)) {
    problem(`Missing current-side ${name} at ${location}`);
  }
  const value = node.attributes[name];
  if (value === 'n.a.' && !allowNotApplicable) {
    problem(`No current-side ${name} at ${location}`);
  }
  return value === 'n.a.' ? null : value;
}

function currentNode(node) {
  return node.attributes.changeStatus !== 'REMOVED';
}

function sortedUniqueStrings(values, location) {
  const sorted = [...values].sort(bytewiseCompare);
  for (let index = 1; index < sorted.length; index += 1) {
    if (sorted[index] === sorted[index - 1]) {
      problem(`Duplicate ${location} value ${JSON.stringify(sorted[index])}`);
    }
  }
  return sorted;
}

function sortedUniqueObjects(values, location) {
  const lines = values.map((value) => JSON.stringify(value)).sort(bytewiseCompare);
  for (let index = 1; index < lines.length; index += 1) {
    if (lines[index] === lines[index - 1]) {
      problem(`Duplicate ${location} value ${lines[index]}`);
    }
  }
  return lines.map((line) => JSON.parse(line));
}

function annotationValueSignature(node) {
  const attributes = {};
  for (const name of ['fullyQualifiedName', 'name', 'type', 'value']) {
    if (Object.hasOwn(node.attributes, name)) attributes[name] = node.attributes[name];
  }
  return {
    attributes,
    values: children(node, 'values').map(annotationValueSignature),
  };
}

function currentAnnotationSignatures(node, location) {
  const annotations = children(node, 'annotations')
    .filter(currentNode)
    .map((annotation) => {
      const annotationName = annotation.attributes.fullyQualifiedName;
      const elements = children(annotation, 'elements')
        .filter(currentNode)
        .map((element) => ({
          name: element.attributes.name,
          values: children(element, 'newElementValues').map(annotationValueSignature),
        }));
      elements.sort((left, right) => bytewiseCompare(left.name, right.name));
      for (let index = 1; index < elements.length; index += 1) {
        if (elements[index].name === elements[index - 1].name) {
          problem(`Duplicate annotation element ${annotationName}.${elements[index].name} at ${location}`);
        }
      }
      return { name: annotationName, elements };
    });
  return sortedUniqueObjects(annotations, `${location} annotation`);
}

function genericTypeSignature(node) {
  return {
    type: node.attributes.type,
    genericWildCard: Object.hasOwn(node.attributes, 'genericWildCard')
      ? node.attributes.genericWildCard
      : null,
    genericTypes: children(node, 'genericTypes').map(genericTypeSignature),
  };
}

function currentGenericTypes(node, containerName) {
  return children(node, containerName).map(genericTypeSignature);
}

function currentGenericTemplateSignatures(node, location) {
  return children(node, 'genericTemplates')
    .filter(currentNode)
    .map((template) => ({
      name: template.attributes.name,
      type: currentAttribute(template, 'newType',
        `${location} generic template ${template.attributes.name}`),
      genericTypes: currentGenericTypes(template, 'newGenericTypes'),
      interfaceTypes: currentGenericTypes(template, 'newInterfaceTypes'),
    }));
}

function currentFacetValues(node, containerName, location) {
  return sortedUniqueStrings(
    children(node, containerName)
      .filter(currentNode)
      .map((facet) => currentAttribute(facet, 'newValue', location)),
    location,
  );
}

function currentExceptionNames(node, location) {
  return sortedUniqueStrings(
    children(node, 'exceptions').filter(currentNode)
      .map((exception) => exception.attributes.name),
    location,
  );
}

function currentParameterSignatures(node) {
  return children(node, 'parameters').map((parameter) => ({
    type: parameter.attributes.type,
    templateName: Object.hasOwn(parameter.attributes, 'templateName')
      ? parameter.attributes.templateName
      : null,
    genericTypes: currentGenericTypes(parameter, 'newGenericTypes'),
  }));
}

function currentReturnTypeSignature(method, owner) {
  const returnType = child(method, 'returnType');
  if (returnType === undefined) {
    problem(`Method ${owner}#${method.attributes.name} has no current return type`);
  }
  return {
    type: currentAttribute(returnType, 'newValue',
      `${owner}#${method.attributes.name} return type`),
    genericTypes: currentGenericTypes(returnType, 'newGenericTypes'),
  };
}

function currentMethodIdentity(owner, method) {
  const parameters = children(method, 'parameters')
    .map((parameter, index) => descriptor(
      parameter.attributes.type,
      `${owner}#${method.attributes.name} current parameter ${index}`,
    ))
    .join('');
  const returnType = currentReturnTypeSignature(method, owner).type;
  return `M:${owner}#${method.attributes.name}(${parameters})${descriptor(
    returnType,
    `${owner}#${method.attributes.name} current return type`,
    true,
  )}`;
}

function currentFieldIdentity(owner, field) {
  const type = child(field, 'type');
  if (type === undefined) problem(`Field ${owner}#${field.attributes.name} has no <type>`);
  const currentType = currentAttribute(type, 'newValue',
    `${owner}#${field.attributes.name} current field type`);
  return `F:${owner}#${field.attributes.name}:${descriptor(
    currentType,
    `${owner}#${field.attributes.name} current field type`,
  )}`;
}

function currentClassApi(classNode, owner) {
  const classType = child(classNode, 'classType');
  if (classType === undefined) problem(`Class ${owner} has no <classType>`);
  const superclass = child(classNode, 'superclass');
  return {
    annotations: currentAnnotationSignatures(classNode, owner),
    attributes: currentFacetValues(classNode, 'attributes',
      `${owner} class attribute`),
    classType: currentAttribute(classType, 'newType', `${owner} class type`),
    genericTemplates: currentGenericTemplateSignatures(classNode, owner),
    interfaces: sortedUniqueStrings(
      children(classNode, 'interfaces').filter(currentNode)
        .map((interfaceNode) => interfaceNode.attributes.fullyQualifiedName),
      `${owner} interface`,
    ),
    modifiers: currentFacetValues(classNode, 'modifiers',
      `${owner} class modifier`),
    superclass: superclass === undefined
      ? null
      : currentAttribute(superclass, 'superclassNew', `${owner} superclass`, true),
  };
}

function currentBehaviorApi(node, owner, method) {
  const api = {
    annotations: currentAnnotationSignatures(node, owner),
    attributes: currentFacetValues(node, 'attributes',
      `${owner} behavior attribute`),
    exceptions: currentExceptionNames(node, `${owner} behavior exception`),
    genericTemplates: currentGenericTemplateSignatures(node, owner),
    modifiers: currentFacetValues(node, 'modifiers',
      `${owner} behavior modifier`),
    parameters: currentParameterSignatures(node),
  };
  if (method) api.returnType = currentReturnTypeSignature(node, owner);
  return api;
}

function currentFieldApi(field, owner) {
  const type = child(field, 'type');
  if (type === undefined) problem(`Field ${owner}#${field.attributes.name} has no <type>`);
  return {
    annotations: currentAnnotationSignatures(field, owner),
    attributes: currentFacetValues(field, 'attributes',
      `${owner} field attribute`),
    genericTypes: currentGenericTypes(field, 'newGenericTypes'),
    modifiers: currentFacetValues(field, 'modifiers',
      `${owner} field modifier`),
    type: currentAttribute(type, 'newValue',
      `${owner}#${field.attributes.name} current field type`),
  };
}

function addApiSignatureRecord(records, record) {
  if (records.has(record.id)) problem(`Ambiguous duplicate API signature identity ${record.id}`);
  records.set(record.id, record);
}

function collectApiSignatureRecords(root, selectedTypeNames) {
  const selected = new Set(selectedTypeNames);
  const found = new Set();
  const classNodes = new Map();
  for (const classNode of root.children[0].children) {
    const typeName = classNode.attributes.fullyQualifiedName;
    if (classNodes.has(typeName)) problem(`Ambiguous duplicate class ${typeName}`);
    classNodes.set(typeName, classNode);
  }

  const records = new Map();
  for (const typeName of selectedTypeNames) {
    const classNode = classNodes.get(typeName);
    if (classNode === undefined) {
      problem(`Selected API type ${typeName} is absent from the japicmp modification report`);
    }
    if (!currentNode(classNode)) {
      problem(`Selected API type ${typeName} has no current-side API`);
    }
    found.add(typeName);
    const owner = binaryName(typeName, '<class>');
    addApiSignatureRecord(records, {
      id: `C:${owner}`,
      kind: 'class',
      api: currentClassApi(classNode, owner),
    });
    for (const constructor of children(classNode, 'constructors').filter(currentNode)) {
      addApiSignatureRecord(records, {
        id: constructorIdentity(owner, constructor).id,
        kind: 'constructor',
        api: currentBehaviorApi(constructor, owner, false),
      });
    }
    for (const field of children(classNode, 'fields').filter(currentNode)) {
      addApiSignatureRecord(records, {
        id: currentFieldIdentity(owner, field),
        kind: 'field',
        api: currentFieldApi(field, owner),
      });
    }
    for (const method of children(classNode, 'methods').filter(currentNode)) {
      addApiSignatureRecord(records, {
        id: currentMethodIdentity(owner, method),
        kind: 'method',
        api: currentBehaviorApi(method, owner, true),
      });
    }
  }
  if (found.size !== selected.size) problem('Selected API type discovery is incomplete');
  return records;
}

function canonicalizeApiSignatureRecords(records) {
  const lines = [...records.values()]
    .sort((left, right) => bytewiseCompare(left.id, right.id))
    .map((record) => JSON.stringify(record));
  return lines.length === 0 ? '' : `${lines.join('\n')}\n`;
}

export function apiSignatureJsonlFromXml(xmlText, includeText) {
  const root = parseXml(xmlText);
  validateRoot(root, false);
  const typeNames = reviewedTypeNames(includeText, 'API include inventory');
  if (typeNames.length === 0) problem('API include inventory must not be empty');
  return canonicalizeApiSignatureRecords(collectApiSignatureRecords(root, typeNames));
}

function expectApiObject(value, keys, location) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    problem(`${location} must be an object`);
  }
  const actualKeys = Object.keys(value);
  if (actualKeys.length !== keys.length ||
      actualKeys.some((key, index) => key !== keys[index])) {
    problem(`${location} has unknown, missing, or noncanonical fields`);
  }
}

function expectApiString(value, location, nullable = false) {
  if ((nullable && value === null) || (typeof value === 'string' && value.length !== 0)) return;
  problem(`${location} must be ${nullable ? 'null or ' : ''}a nonempty string`);
}

function validateReviewedStringArray(value, location, sorted) {
  if (!Array.isArray(value)) problem(`${location} must be an array`);
  value.forEach((entry, index) => expectApiString(entry, `${location}[${index}]`));
  if (sorted) {
    const canonical = sortedUniqueStrings(value, location);
    if (canonical.some((entry, index) => entry !== value[index])) {
      problem(`${location} is not in canonical bytewise order`);
    }
  }
}

function validateReviewedAnnotationValue(value, location) {
  expectApiObject(value, ['attributes', 'values'], location);
  if (value.attributes === null || typeof value.attributes !== 'object' ||
      Array.isArray(value.attributes)) {
    problem(`${location}.attributes must be an object`);
  }
  const allowed = ['fullyQualifiedName', 'name', 'type', 'value'];
  let previous = -1;
  for (const name of Object.keys(value.attributes)) {
    const position = allowed.indexOf(name);
    if (position === -1 || position <= previous) {
      problem(`${location}.attributes has unknown or noncanonical fields`);
    }
    expectApiString(value.attributes[name], `${location}.attributes.${name}`);
    previous = position;
  }
  if (!Array.isArray(value.values)) problem(`${location}.values must be an array`);
  value.values.forEach((entry, index) =>
    validateReviewedAnnotationValue(entry, `${location}.values[${index}]`));
}

function validateReviewedAnnotations(value, location) {
  if (!Array.isArray(value)) problem(`${location} must be an array`);
  value.forEach((annotation, annotationIndex) => {
    const annotationLocation = `${location}[${annotationIndex}]`;
    expectApiObject(annotation, ['name', 'elements'], annotationLocation);
    expectApiString(annotation.name, `${annotationLocation}.name`);
    if (!Array.isArray(annotation.elements)) {
      problem(`${annotationLocation}.elements must be an array`);
    }
    let previousElement;
    annotation.elements.forEach((element, elementIndex) => {
      const elementLocation = `${annotationLocation}.elements[${elementIndex}]`;
      expectApiObject(element, ['name', 'values'], elementLocation);
      expectApiString(element.name, `${elementLocation}.name`);
      if (previousElement !== undefined && bytewiseCompare(previousElement, element.name) >= 0) {
        problem(`${annotationLocation}.elements is not in canonical name order`);
      }
      previousElement = element.name;
      if (!Array.isArray(element.values)) problem(`${elementLocation}.values must be an array`);
      element.values.forEach((entry, valueIndex) =>
        validateReviewedAnnotationValue(entry, `${elementLocation}.values[${valueIndex}]`));
    });
  });
  const canonical = sortedUniqueObjects(value, location);
  if (canonical.some((entry, index) => JSON.stringify(entry) !== JSON.stringify(value[index]))) {
    problem(`${location} is not in canonical bytewise order`);
  }
}

function validateReviewedGenericTypes(value, location) {
  if (!Array.isArray(value)) problem(`${location} must be an array`);
  value.forEach((genericType, index) => {
    const genericLocation = `${location}[${index}]`;
    expectApiObject(genericType, ['type', 'genericWildCard', 'genericTypes'], genericLocation);
    expectApiString(genericType.type, `${genericLocation}.type`);
    if (genericType.genericWildCard !== null &&
        !GENERIC_WILDCARDS.has(genericType.genericWildCard)) {
      problem(`${genericLocation}.genericWildCard is invalid`);
    }
    validateReviewedGenericTypes(genericType.genericTypes, `${genericLocation}.genericTypes`);
  });
}

function validateReviewedGenericTemplates(value, location) {
  if (!Array.isArray(value)) problem(`${location} must be an array`);
  value.forEach((template, index) => {
    const templateLocation = `${location}[${index}]`;
    expectApiObject(template, ['name', 'type', 'genericTypes', 'interfaceTypes'], templateLocation);
    expectApiString(template.name, `${templateLocation}.name`);
    expectApiString(template.type, `${templateLocation}.type`);
    validateReviewedGenericTypes(template.genericTypes, `${templateLocation}.genericTypes`);
    validateReviewedGenericTypes(template.interfaceTypes, `${templateLocation}.interfaceTypes`);
  });
}

function validateReviewedParameters(value, location) {
  if (!Array.isArray(value)) problem(`${location} must be an array`);
  value.forEach((parameter, index) => {
    const parameterLocation = `${location}[${index}]`;
    expectApiObject(parameter, ['type', 'templateName', 'genericTypes'], parameterLocation);
    expectApiString(parameter.type, `${parameterLocation}.type`);
    expectApiString(parameter.templateName, `${parameterLocation}.templateName`, true);
    validateReviewedGenericTypes(parameter.genericTypes, `${parameterLocation}.genericTypes`);
  });
}

function validateReviewedClassApi(api, location) {
  expectApiObject(api, [
    'annotations', 'attributes', 'classType', 'genericTemplates',
    'interfaces', 'modifiers', 'superclass',
  ], location);
  validateReviewedAnnotations(api.annotations, `${location}.annotations`);
  validateReviewedStringArray(api.attributes, `${location}.attributes`, true);
  expectApiString(api.classType, `${location}.classType`);
  validateReviewedGenericTemplates(api.genericTemplates, `${location}.genericTemplates`);
  validateReviewedStringArray(api.interfaces, `${location}.interfaces`, true);
  validateReviewedStringArray(api.modifiers, `${location}.modifiers`, true);
  expectApiString(api.superclass, `${location}.superclass`, true);
}

function validateReviewedBehaviorApi(api, location, method) {
  const keys = [
    'annotations', 'attributes', 'exceptions', 'genericTemplates',
    'modifiers', 'parameters',
  ];
  if (method) keys.push('returnType');
  expectApiObject(api, keys, location);
  validateReviewedAnnotations(api.annotations, `${location}.annotations`);
  validateReviewedStringArray(api.attributes, `${location}.attributes`, true);
  validateReviewedStringArray(api.exceptions, `${location}.exceptions`, true);
  validateReviewedGenericTemplates(api.genericTemplates, `${location}.genericTemplates`);
  validateReviewedStringArray(api.modifiers, `${location}.modifiers`, true);
  validateReviewedParameters(api.parameters, `${location}.parameters`);
  if (method) {
    expectApiObject(api.returnType, ['type', 'genericTypes'], `${location}.returnType`);
    expectApiString(api.returnType.type, `${location}.returnType.type`);
    validateReviewedGenericTypes(api.returnType.genericTypes,
      `${location}.returnType.genericTypes`);
  }
}

function validateReviewedFieldApi(api, location) {
  expectApiObject(api,
    ['annotations', 'attributes', 'genericTypes', 'modifiers', 'type'], location);
  validateReviewedAnnotations(api.annotations, `${location}.annotations`);
  validateReviewedStringArray(api.attributes, `${location}.attributes`, true);
  validateReviewedGenericTypes(api.genericTypes, `${location}.genericTypes`);
  validateReviewedStringArray(api.modifiers, `${location}.modifiers`, true);
  expectApiString(api.type, `${location}.type`);
}

function parsedReviewedApiSignatures(reviewedText) {
  if (reviewedText.includes('\r')) problem('Reviewed API signatures must use LF line endings');
  if (reviewedText.length !== 0 && !reviewedText.endsWith('\n')) {
    problem('Reviewed API signatures must end with LF');
  }
  const records = new Map();
  for (const [index, line] of reviewedText.split('\n').entries()) {
    if (line.length === 0) continue;
    let record;
    try {
      record = JSON.parse(line);
    } catch (error) {
      problem(`Reviewed API signature line ${index + 1} is invalid JSON: ${error.message}`);
    }
    expectApiObject(record, ['id', 'kind', 'api'], `Reviewed API signature line ${index + 1}`);
    if (typeof record.id !== 'string' || typeof record.kind !== 'string' ||
        record.api === null || typeof record.api !== 'object' || Array.isArray(record.api)) {
      problem(`Reviewed API signature line ${index + 1} has invalid field types`);
    }
    const prefix = record.kind === 'class' ? 'C:'
      : record.kind === 'field' ? 'F:'
        : record.kind === 'constructor' || record.kind === 'method' ? 'M:' : undefined;
    if (prefix === undefined || !record.id.startsWith(prefix)) {
      problem(`Reviewed API signature line ${index + 1} ID does not match kind ${record.kind}`);
    }
    if (record.kind === 'class') {
      validateReviewedClassApi(record.api, `Reviewed API signature line ${index + 1}.api`);
    } else if (record.kind === 'field') {
      validateReviewedFieldApi(record.api, `Reviewed API signature line ${index + 1}.api`);
    } else {
      validateReviewedBehaviorApi(record.api,
        `Reviewed API signature line ${index + 1}.api`, record.kind === 'method');
    }
    if (JSON.stringify(record) !== line) {
      problem(`Reviewed API signature line ${index + 1} is not compact canonical JSON`);
    }
    if (records.has(record.id)) problem(`Reviewed API signatures contain duplicate ID ${record.id}`);
    records.set(record.id, record);
  }
  const canonical = canonicalizeApiSignatureRecords(records);
  if (canonical !== reviewedText) {
    problem('Reviewed API signatures are not in canonical bytewise-sorted form');
  }
  return records;
}

export function verifyReviewedApiSignatures(xmlText, includeText, reviewedText) {
  const reviewedRecords = parsedReviewedApiSignatures(reviewedText);
  const actualText = apiSignatureJsonlFromXml(xmlText, includeText);
  if (actualText === reviewedText) return;

  const actualRecords = new Map();
  for (const line of actualText.split('\n').filter(Boolean)) {
    const record = JSON.parse(line);
    actualRecords.set(record.id, line);
  }
  const reviewedLines = new Map([...reviewedRecords].map(([id, record]) =>
    [id, JSON.stringify(record)]));
  const unexpected = [...actualRecords.keys()]
    .filter((id) => !reviewedLines.has(id)).sort(bytewiseCompare);
  const missing = [...reviewedLines.keys()]
    .filter((id) => !actualRecords.has(id)).sort(bytewiseCompare);
  const changed = [...actualRecords.keys()]
    .filter((id) => reviewedLines.has(id) && actualRecords.get(id) !== reviewedLines.get(id))
    .sort(bytewiseCompare);
  problem([
    'japicmp selected API signatures differ from the reviewed snapshot',
    `unexpected (${unexpected.length}): ${unexpected.join(', ') || 'none'}`,
    `missing (${missing.length}): ${missing.join(', ') || 'none'}`,
    `changed (${changed.length}): ${changed.join(', ') || 'none'}`,
  ].join('\n'));
}

function hasCurrentChangedNode(nodes) {
  return nodes.some((node) => currentNode(node) &&
    (node.attributes.changeStatus === 'NEW' || node.attributes.changeStatus === 'MODIFIED'));
}

function classHasCurrentApiDelta(classNode) {
  if (!currentNode(classNode)) return false;
  if (classNode.attributes.changeStatus === 'NEW') return true;
  if (hasCurrentChangedNode(children(classNode, 'annotations')) ||
      hasCurrentChangedNode(children(classNode, 'attributes')) ||
      hasCurrentChangedNode(children(classNode, 'constructors')) ||
      hasCurrentChangedNode(children(classNode, 'fields')) ||
      hasCurrentChangedNode(children(classNode, 'genericTemplates')) ||
      hasCurrentChangedNode(children(classNode, 'interfaces')) ||
      hasCurrentChangedNode(children(classNode, 'methods')) ||
      hasCurrentChangedNode(children(classNode, 'modifiers'))) {
    return true;
  }
  const classType = child(classNode, 'classType');
  if (classType !== undefined && currentNode(classType) &&
      (classType.attributes.changeStatus === 'NEW' ||
        classType.attributes.changeStatus === 'MODIFIED')) {
    return true;
  }
  const superclass = child(classNode, 'superclass');
  return superclass !== undefined && currentNode(superclass) &&
    (superclass.attributes.changeStatus === 'NEW' ||
      superclass.attributes.changeStatus === 'MODIFIED');
}

function isMcpApiTypeName(typeName) {
  return typeName.startsWith('com.soklet.Mcp') ||
    typeName.startsWith('com.soklet.annotation.Mcp');
}

function annotationValueReferencesMcp(node) {
  for (const name of ['fullyQualifiedName', 'type']) {
    if (Object.hasOwn(node.attributes, name) && isMcpApiTypeName(node.attributes[name])) {
      return true;
    }
  }
  return children(node, 'values').some(annotationValueReferencesMcp);
}

function annotationsReferenceMcp(node) {
  return children(node, 'annotations').filter(currentNode).some((annotation) =>
    isMcpApiTypeName(annotation.attributes.fullyQualifiedName) ||
      children(annotation, 'elements').filter(currentNode).some((element) =>
        children(element, 'newElementValues').some(annotationValueReferencesMcp)));
}

function genericTypeReferencesMcp(node) {
  return isMcpApiTypeName(node.attributes.type) ||
    children(node, 'genericTypes').some(genericTypeReferencesMcp);
}

function currentGenericTypesReferenceMcp(node, containerName) {
  return children(node, containerName).some(genericTypeReferencesMcp);
}

function genericTemplatesReferenceMcp(node) {
  return children(node, 'genericTemplates').filter(currentNode).some((template) =>
    (Object.hasOwn(template.attributes, 'newType') &&
      isMcpApiTypeName(template.attributes.newType)) ||
      currentGenericTypesReferenceMcp(template, 'newGenericTypes') ||
      currentGenericTypesReferenceMcp(template, 'newInterfaceTypes'));
}

function behaviorReferencesMcp(node) {
  if (annotationsReferenceMcp(node) || genericTemplatesReferenceMcp(node) ||
      children(node, 'exceptions').filter(currentNode)
        .some((exception) => isMcpApiTypeName(exception.attributes.name))) {
    return true;
  }
  for (const parameter of children(node, 'parameters')) {
    if (isMcpApiTypeName(parameter.attributes.type) ||
        currentGenericTypesReferenceMcp(parameter, 'newGenericTypes')) {
      return true;
    }
  }
  const returnType = child(node, 'returnType');
  return returnType !== undefined && currentNode(returnType) &&
    (isMcpApiTypeName(returnType.attributes.newValue) ||
      currentGenericTypesReferenceMcp(returnType, 'newGenericTypes'));
}

function fieldReferencesMcp(field) {
  const type = child(field, 'type');
  return annotationsReferenceMcp(field) ||
    (type !== undefined && currentNode(type) && isMcpApiTypeName(type.attributes.newValue)) ||
    currentGenericTypesReferenceMcp(field, 'newGenericTypes');
}

function classReferencesMcp(classNode) {
  if (annotationsReferenceMcp(classNode) || genericTemplatesReferenceMcp(classNode) ||
      children(classNode, 'interfaces').filter(currentNode)
        .some((interfaceNode) => isMcpApiTypeName(interfaceNode.attributes.fullyQualifiedName))) {
    return true;
  }
  const superclass = child(classNode, 'superclass');
  if (superclass !== undefined && currentNode(superclass) &&
      isMcpApiTypeName(superclass.attributes.superclassNew)) {
    return true;
  }
  return children(classNode, 'constructors').filter(currentNode).some(behaviorReferencesMcp) ||
    children(classNode, 'fields').filter(currentNode).some(fieldReferencesMcp) ||
    children(classNode, 'methods').filter(currentNode).some(behaviorReferencesMcp);
}

function reviewedApiOwners(root) {
  const owners = [];
  const seen = new Set();
  for (const classNode of root.children[0].children) {
    const typeName = classNode.attributes.fullyQualifiedName;
    if (seen.has(typeName)) problem(`Ambiguous duplicate class ${typeName}`);
    seen.add(typeName);
    if (!currentNode(classNode) || typeName.startsWith('com.soklet.internal.')) continue;
    if (isMcpApiTypeName(typeName) || classReferencesMcp(classNode) ||
        classHasCurrentApiDelta(classNode)) {
      owners.push(typeName);
    }
  }
  return owners.sort(bytewiseCompare);
}

export function verifyReviewedApiInventory(xmlText, nonMcpText, phaseIncludeTexts) {
  if (!Array.isArray(phaseIncludeTexts) || phaseIncludeTexts.length === 0) {
    problem('At least one phase API include inventory is required');
  }
  const root = parseXml(xmlText);
  validateRoot(root, false);
  const reviewedOwners = new Map();
  const inventories = [
    { location: 'non-MCP public API allowlist', text: nonMcpText },
    ...phaseIncludeTexts.map((text, index) => ({
      location: `phase API include inventory ${index + 1}`,
      text,
    })),
  ];
  for (const inventory of inventories) {
    for (const typeName of reviewedTypeNames(inventory.text, inventory.location)) {
      const previous = reviewedOwners.get(typeName);
      if (previous !== undefined) {
        problem(`Public API type ${typeName} appears in both ${previous} and ${inventory.location}`);
      }
      reviewedOwners.set(typeName, inventory.location);
    }
  }

  const actual = reviewedApiOwners(root);
  const actualSet = new Set(actual);
  const expected = [...reviewedOwners.keys()].sort(bytewiseCompare);
  const expectedSet = new Set(expected);
  const unexpected = actual.filter((typeName) => !expectedSet.has(typeName));
  const missing = expected.filter((typeName) => !actualSet.has(typeName));
  if (unexpected.length !== 0 || missing.length !== 0) {
    problem([
      'japicmp current-side API ownership differs from the reviewed inventories',
      `unexpected (${unexpected.length}): ${unexpected.join(', ') || 'none'}`,
      `missing (${missing.length}): ${missing.join(', ') || 'none'}`,
    ].join('\n'));
  }
  return actual.length;
}

export function incompatibilityJsonlFromXml(xmlText) {
  const root = parseXml(xmlText);
  validateRoot(root, true);
  return canonicalizeRecords(collectRecords(root));
}

export function readUtf8(path) {
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(readFileSync(path));
  } catch (error) {
    problem(`Unable to read ${path} as UTF-8: ${error.message}`);
  }
}

function normalizeReviewedChange(value, lineNumber, changeNumber, kind) {
  const location = `Reviewed JSONL line ${lineNumber} change ${changeNumber}`;
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    problem(`${location} is not a removal/incompatibility change`);
  }
  const expectedKeys = new Set([
    'type', 'site', 'binaryCompatible', 'sourceCompatible',
  ]);
  const keys = Object.keys(value);
  if (keys.length !== expectedKeys.size || keys.some((key) => !expectedKeys.has(key))) {
    problem(`${location} has an unknown or missing field`);
  }
  if (!COMPATIBILITY_CHANGE_TYPES.has(value.type)) {
    problem(`${location} has an unknown japicmp compatibility type`);
  }
  if (typeof value.site !== 'string' || value.site.length === 0 || /[\u0000-\u001f]/.test(value.site)) {
    problem(`${location} has an invalid site`);
  }
  if (typeof value.binaryCompatible !== 'boolean' ||
      typeof value.sourceCompatible !== 'boolean') {
    problem(`${location} has invalid compatibility flags`);
  }
  const directRemoval = DIRECT_SYMBOL_REMOVALS[kind];
  if (value.binaryCompatible && value.sourceCompatible &&
      (value.type !== directRemoval.type || value.site !== directRemoval.site)) {
    problem(`${location} is compatible but is not the direct ${kind} removal`);
  }
  return {
    type: value.type,
    site: value.site,
    binaryCompatible: value.binaryCompatible,
    sourceCompatible: value.sourceCompatible,
  };
}

function normalizeReviewedRecord(value, lineNumber) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    problem(`Reviewed JSONL line ${lineNumber} is not a removal/incompatibility-symbol record`);
  }
  const allowedKeys = new Set(['id', 'newId', 'kind', 'changes']);
  const keys = Object.keys(value);
  if (keys.some((key) => !allowedKeys.has(key)) ||
      !Object.hasOwn(value, 'id') || !Object.hasOwn(value, 'kind') ||
      !Object.hasOwn(value, 'changes')) {
    problem(`Reviewed JSONL line ${lineNumber} has an unknown or missing field`);
  }
  const kinds = new Set(['class', 'constructor', 'field', 'method']);
  if (typeof value.id !== 'string' || value.id.length === 0 ||
      /[\u0000-\u001f]/.test(value.id) || !kinds.has(value.kind) ||
      !Array.isArray(value.changes) || value.changes.length === 0) {
    problem(`Reviewed JSONL line ${lineNumber} is not a removal/incompatibility-symbol record`);
  }
  const expectedPrefix = value.kind === 'class' ? 'C:' : value.kind === 'field' ? 'F:' : 'M:';
  if (!value.id.startsWith(expectedPrefix)) {
    problem(`Reviewed JSONL line ${lineNumber} ID does not match kind ${value.kind}`);
  }
  let newId;
  if (Object.hasOwn(value, 'newId')) {
    if (typeof value.newId !== 'string' || value.newId.length === 0 ||
        !value.newId.startsWith(expectedPrefix) || /[\u0000-\u001f]/.test(value.newId)) {
      problem(`Reviewed JSONL line ${lineNumber} has an invalid newId`);
    }
    newId = value.newId;
  }
  return {
    id: value.id,
    newId,
    kind: value.kind,
    changes: value.changes.map((change, index) =>
      normalizeReviewedChange(change, lineNumber, index + 1, value.kind)),
  };
}

export function verifyReviewedSet(xmlText, reviewedText) {
  if (reviewedText.includes('\r')) problem('Reviewed JSONL set must use LF line endings');
  if (reviewedText.length !== 0 && !reviewedText.endsWith('\n')) {
    problem('Reviewed JSONL set must end with LF');
  }
  const reviewedRecords = new Map();
  const parsed = new Map();
  for (const [index, line] of reviewedText.split('\n').entries()) {
    if (line.length === 0) continue;
    let value;
    try {
      value = JSON.parse(line);
    } catch (error) {
      problem(`Reviewed JSONL line ${index + 1} is invalid JSON: ${error.message}`);
    }
    const record = normalizeReviewedRecord(value, index + 1);
    if (parsed.has(record.id)) problem(`Reviewed JSONL contains duplicate ID ${record.id}`);
    parsed.set(record.id, record);
    reviewedRecords.set(record.id, line);
  }
  const canonicalReviewed = canonicalizeRecords(parsed);
  if (canonicalReviewed !== reviewedText) {
    problem('Reviewed JSONL is not in canonical bytewise-sorted form');
  }

  const actualText = incompatibilityJsonlFromXml(xmlText);
  if (actualText === reviewedText) return;
  const actualRecords = new Map();
  for (const line of actualText.split('\n').filter(Boolean)) {
    const value = JSON.parse(line);
    actualRecords.set(value.id, line);
  }
  const unexpected = [...actualRecords.keys()]
    .filter((id) => !reviewedRecords.has(id))
    .sort(bytewiseCompare);
  const missing = [...reviewedRecords.keys()]
    .filter((id) => !actualRecords.has(id))
    .sort(bytewiseCompare);
  const changed = [...actualRecords.keys()]
    .filter((id) => reviewedRecords.has(id) && actualRecords.get(id) !== reviewedRecords.get(id))
    .sort(bytewiseCompare);
  problem([
    'japicmp removal/incompatibility set differs from the reviewed set in one or both directions',
    `unexpected (${unexpected.length}): ${unexpected.join(', ') || 'none'}`,
    `missing (${missing.length}): ${missing.join(', ') || 'none'}`,
    `changed (${changed.length}): ${changed.join(', ') || 'none'}`,
  ].join('\n'));
}

function usage() {
  problem([
    'Usage:',
    '  japicmp-symbols.mjs --extract <japicmp.xml> <symbols.jsonl>',
    '  japicmp-symbols.mjs --verify <japicmp.xml> <reviewed.jsonl>',
    '  japicmp-symbols.mjs --extract-signatures <japicmp.xml> <phase.includes> <signatures.jsonl>',
    '  japicmp-symbols.mjs --verify-signatures <japicmp.xml> <phase.includes> <reviewed-signatures.jsonl>',
    '  japicmp-symbols.mjs --verify-inventory <japicmp.xml> <non-mcp.allowlist> <phase-4.includes> <phase-5.includes> <phase-6.includes> <provisional.includes>',
    '  japicmp-symbols.mjs --verify-report-pair <modified-only.xml> <full.xml>',
  ].join('\n'));
}

function main() {
  const [command, ...paths] = process.argv.slice(2);
  if (command === '--extract' && paths.length === 2) {
    const [xmlPath, setPath] = paths;
    const xml = readUtf8(xmlPath);
    const output = incompatibilityJsonlFromXml(xml);
    writeFileSync(setPath, output, { encoding: 'utf8', flag: 'w' });
    process.stdout.write(`Wrote ${output === '' ? 0 : output.split('\n').length - 1} canonical removal/incompatibility symbol(s): ${setPath}\n`);
  } else if (command === '--verify' && paths.length === 2) {
    const [xmlPath, setPath] = paths;
    const xml = readUtf8(xmlPath);
    const reviewed = readUtf8(setPath);
    verifyReviewedSet(xml, reviewed);
    const count = reviewed === '' ? 0 : reviewed.split('\n').length - 1;
    process.stdout.write(`Verified ${count} reviewed removal/incompatibility symbol(s): ${setPath}\n`);
  } else if (command === '--extract-signatures' && paths.length === 3) {
    const [xmlPath, includePath, signaturePath] = paths;
    const output = apiSignatureJsonlFromXml(readUtf8(xmlPath), readUtf8(includePath));
    writeFileSync(signaturePath, output, { encoding: 'utf8', flag: 'w' });
    const count = output === '' ? 0 : output.split('\n').length - 1;
    process.stdout.write(`Wrote ${count} canonical selected API signature(s): ${signaturePath}\n`);
  } else if (command === '--verify-signatures' && paths.length === 3) {
    const [xmlPath, includePath, signaturePath] = paths;
    const reviewed = readUtf8(signaturePath);
    verifyReviewedApiSignatures(readUtf8(xmlPath), readUtf8(includePath), reviewed);
    const count = reviewed === '' ? 0 : reviewed.split('\n').length - 1;
    process.stdout.write(`Verified ${count} reviewed selected API signature(s): ${signaturePath}\n`);
  } else if (command === '--verify-inventory' && paths.length === 6) {
    const [xmlPath, nonMcpPath, ...includePaths] = paths;
    const count = verifyReviewedApiInventory(
      readUtf8(xmlPath),
      readUtf8(nonMcpPath),
      includePaths.map(readUtf8),
    );
    process.stdout.write(`Verified ${count} reviewed current-side API owner(s)\n`);
  } else if (command === '--verify-report-pair' && paths.length === 2) {
    const [modifiedOnlyPath, fullPath] = paths;
    verifyJapicmpReportPair(
      readUtf8(modifiedOnlyPath),
      readUtf8(fullPath),
      '3.5.1',
      'soklet-3.5.1.jar',
    );
    process.stdout.write('Verified matched japicmp report pair against Soklet 3.5.1\n');
  } else {
    usage();
  }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    main();
  } catch (error) {
    if (error instanceof ApiDiffError) {
      process.stderr.write(`${error.message}\n`);
      process.exit(1);
    }
    throw error;
  }
}
