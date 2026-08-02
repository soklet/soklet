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

function validateRoot(root) {
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
      root.attributes.onlyBinaryIncompatibleModifications !== 'false' ||
      root.attributes.onlyModifications !== 'true' ||
      root.attributes.packagesExclude !== 'n.a.' ||
      root.attributes.packagesInclude !== 'all') {
    problem('japicmp report does not satisfy the complete public/protected comparison contract');
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

export function incompatibilityJsonlFromXml(xmlText) {
  const root = parseXml(xmlText);
  validateRoot(root);
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
  problem('Usage: japicmp-symbols.mjs --extract <japicmp.xml> <symbols.jsonl> | --verify <japicmp.xml> <reviewed.jsonl>');
}

function main() {
  const [command, xmlPath, setPath, ...rest] = process.argv.slice(2);
  if (rest.length !== 0 || xmlPath === undefined || setPath === undefined) usage();
  const xml = readUtf8(xmlPath);
  if (command === '--extract') {
    const output = incompatibilityJsonlFromXml(xml);
    writeFileSync(setPath, output, { encoding: 'utf8', flag: 'w' });
    process.stdout.write(`Wrote ${output === '' ? 0 : output.split('\n').length - 1} canonical removal/incompatibility symbol(s): ${setPath}\n`);
  } else if (command === '--verify') {
    const reviewed = readUtf8(setPath);
    verifyReviewedSet(xml, reviewed);
    const count = reviewed === '' ? 0 : reviewed.split('\n').length - 1;
    process.stdout.write(`Verified ${count} reviewed removal/incompatibility symbol(s): ${setPath}\n`);
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
