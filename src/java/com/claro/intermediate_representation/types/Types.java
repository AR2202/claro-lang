package com.claro.intermediate_representation.types;

import com.claro.ClaroParserException;
import com.claro.compiler_backends.interpreted.ScopedHeap;
import com.claro.intermediate_representation.expressions.Expr;
import com.claro.intermediate_representation.statements.Stmt;
import com.claro.runtime_utilities.injector.Key;
import com.google.auto.value.AutoValue;
import com.google.common.base.Joiner;
import com.google.common.collect.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

// TODO(steving) This class needs refactoring into a standalone package.
public final class Types {
  public static final Type INTEGER = ConcreteType.create(BaseType.INTEGER);
  public static final Type FLOAT = ConcreteType.create(BaseType.FLOAT);
  public static final Type STRING = ConcreteType.create(BaseType.STRING);
  public static final Type BOOLEAN = ConcreteType.create(BaseType.BOOLEAN);
  public static final Type MODULE = ConcreteType.create(BaseType.MODULE);

  // Special type that indicates that the compiler won't be able to determine this type answer until runtime at which
  // point it will potentially fail other runtime type checking. Anywhere where an "UNDECIDED" type is emitted by the
  // compiler we'll require a cast on the expr causing the indecision for the programmer to assert they know what's up.
  public static final Type UNDECIDED = ConcreteType.create(BaseType.UNDECIDED);

  // Special type that indicates that the compiler will *NEVER* be possibly able to determine this type at compile-time
  // specifically because *THERE WAS SOME SORT OF COMPILATION ERROR* caused by the user writing a bug. This will allow
  // compilation to continue after reaching some illegal user code by returning this "UNKNOWABLE" type.
  public static final Type UNKNOWABLE = ConcreteType.create(BaseType.UNKNOWABLE);

  public interface Collection {
    Type getElementType();
  }

  @AutoValue
  public abstract static class NothingType extends Type {
    public static NothingType get() {
      return new AutoValue_Types_NothingType(BaseType.NOTHING, ImmutableMap.of());
    }

    @Override
    public String toString() {
      return "NothingType";
    }

    @Override
    public String getJavaSourceClaroType() {
      return "Types.NothingType.get()";
    }
  }

  @AutoValue
  public abstract static class ListType extends Type implements Collection, SupportsMutableVariant<ListType> {
    public static final String PARAMETERIZED_TYPE_KEY = "$values";

    public abstract boolean getIsMutable();

    public static ListType forValueType(Type valueType) {
      return ListType.forValueType(valueType, /*isMutable=*/false);
    }

    public static ListType forValueType(Type valueType, boolean isMutable) {
      return new AutoValue_Types_ListType(BaseType.LIST, ImmutableMap.of(PARAMETERIZED_TYPE_KEY, valueType), isMutable);
    }

    @Override
    public Type getElementType() {
      return this.parameterizedTypeArgs().get(PARAMETERIZED_TYPE_KEY);
    }

    @Override
    public String toString() {
      String baseFormattedType = super.toString();
      return this.getIsMutable() ? "mut " + baseFormattedType : baseFormattedType;
    }

    @Override
    public String getJavaSourceClaroType() {
      return String.format(
          "Types.ListType.forValueType(%s, %s)",
          this.parameterizedTypeArgs().get(PARAMETERIZED_TYPE_KEY).getJavaSourceClaroType(),
          this.getIsMutable()
      );
    }

    @Override
    public ListType toShallowlyMutableVariant() {
      return new AutoValue_Types_ListType(BaseType.LIST, this.parameterizedTypeArgs(), /*isMutable=*/true);
    }

    @Override
    public Optional<ListType> toDeeplyImmutableVariant() {
      Optional<? extends Type> elementType = Optional.of(getElementType());
      if (elementType.get() instanceof SupportsMutableVariant<?>) {
        elementType = ((SupportsMutableVariant<?>) elementType.get()).toDeeplyImmutableVariant();
      } else if (elementType.get() instanceof UserDefinedType) {
        elementType = ((UserDefinedType) elementType.get()).toDeeplyImmutableVariant();
      } else if (elementType.get() instanceof FutureType) {
        if (!Types.isDeeplyImmutable(
            elementType.get().parameterizedTypeArgs().get(FutureType.PARAMETERIZED_TYPE_KEY))) {
          // If it's not deeply immutable there's nothing that I can do to automatically make it deeply immutable
          // w/o the user manually doing a monadic(?) transform in a graph.
          elementType = Optional.empty();
        }
      }
      return elementType.map(
          type ->
              new AutoValue_Types_ListType(
                  BaseType.LIST, ImmutableMap.of(PARAMETERIZED_TYPE_KEY, type), /*isMutable=*/false));
    }

    @Override
    public boolean isMutable() {
      return this.getIsMutable();
    }
  }

  @AutoValue
  public abstract static class MapType extends Type implements SupportsMutableVariant<MapType> {
    public static final String PARAMETERIZED_TYPE_KEYS = "$keys";
    public static final String PARAMETERIZED_TYPE_VALUES = "$values";

    public abstract boolean getIsMutable();

    public static MapType forKeyValueTypes(Type keysType, Type valuesType) {
      return MapType.forKeyValueTypes(keysType, valuesType, /*isMutable=*/false);
    }

    public static MapType forKeyValueTypes(Type keysType, Type valuesType, boolean isMutable) {
      // TODO(steving) Make it illegal to declare a map wrapping future<...> keys. That's nonsensical in the sense that
      //   there's "nothing" to hash yet.
      return new AutoValue_Types_MapType(BaseType.MAP, ImmutableMap.of(PARAMETERIZED_TYPE_KEYS, keysType, PARAMETERIZED_TYPE_VALUES, valuesType), isMutable);
    }

    @Override
    public String toString() {
      String baseFormattedType = super.toString();
      return this.getIsMutable() ? "mut " + baseFormattedType : baseFormattedType;
    }

    @Override
    public String getJavaSourceClaroType() {
      return String.format(
          "Types.MapType.forKeyValueTypes(%s, %s, %s)",
          this.parameterizedTypeArgs().get(PARAMETERIZED_TYPE_KEYS).getJavaSourceClaroType(),
          this.parameterizedTypeArgs().get(PARAMETERIZED_TYPE_VALUES).getJavaSourceClaroType(),
          this.isMutable()
      );
    }

    @Override
    public MapType toShallowlyMutableVariant() {
      return new AutoValue_Types_MapType(BaseType.MAP, this.parameterizedTypeArgs(), /*isMutable=*/true);
    }

    @Override
    public Optional<MapType> toDeeplyImmutableVariant() {
      // If any of the parameterized types can't be coerced to a deeply-immutable variant then this overall type
      // instance cannot be converted to something deeply-immutable **automatically** (really I'm just saying I
      // wouldn't be able to give a good suggestion).
      ImmutableMap.Builder<String, Type> deeplyImmutableParameterizedTypeVariantsBuilder = ImmutableMap.builder();
      for (Map.Entry<String, Type> paramTypeEntry : this.parameterizedTypeArgs().entrySet()) {
        Optional<? extends Type> elementType = Optional.of(paramTypeEntry.getValue());
        if (elementType.get() instanceof SupportsMutableVariant<?>) {
          elementType = ((SupportsMutableVariant<?>) elementType.get()).toDeeplyImmutableVariant();
        } else if (elementType.get() instanceof UserDefinedType) {
          elementType = ((UserDefinedType) elementType.get()).toDeeplyImmutableVariant();
        } else if (
          // It'll only be possible to use futures in the values of a map, not as the keys of a map.
            paramTypeEntry.getKey().equals(MapType.PARAMETERIZED_TYPE_VALUES)
            && elementType.get() instanceof FutureType) {
          if (!Types.isDeeplyImmutable(
              elementType.get().parameterizedTypeArgs().get(FutureType.PARAMETERIZED_TYPE_KEY))) {
            // If it's not deeply immutable there's nothing that I can do to automatically make it deeply immutable
            // w/o the user manually doing a monadic(?) transform in a graph.
            elementType = Optional.empty();
          }
        }
        if (elementType.isPresent()) {
          deeplyImmutableParameterizedTypeVariantsBuilder.put(paramTypeEntry.getKey(), elementType.get());
        } else {
          return Optional.empty();
        }
      }
      return Optional.of(
          new AutoValue_Types_MapType(
              BaseType.MAP,
              deeplyImmutableParameterizedTypeVariantsBuilder.build(),
              /*isMutable=*/false
          ));
    }

    @Override
    public boolean isMutable() {
      return this.getIsMutable();
    }
  }

  @AutoValue
  public abstract static class SetType extends Type implements SupportsMutableVariant<SetType> {
    public static final String PARAMETERIZED_TYPE = "$values";

    public abstract boolean getIsMutable();

    public static SetType forValueType(Type valueType) {
      return SetType.forValueType(valueType, /*isMutable=*/false);
    }

    public static SetType forValueType(Type valueType, boolean isMutable) {
      // TODO(steving) Make it illegal to declare a set wrapping future<...>. That's nonsensical in the sense that
      //   there's "nothing" to hash yet.
      return new AutoValue_Types_SetType(BaseType.SET, ImmutableMap.of(PARAMETERIZED_TYPE, valueType), isMutable);
    }

    @Override
    public String toString() {
      String baseFormattedType = super.toString();
      return this.getIsMutable() ? "mut " + baseFormattedType : baseFormattedType;
    }

    @Override
    public String getJavaSourceClaroType() {
      return String.format(
          "Types.SetType.forValueType(%s, %s)",
          this.parameterizedTypeArgs().get(PARAMETERIZED_TYPE).getJavaSourceClaroType(),
          this.isMutable()
      );
    }

    @Override
    public SetType toShallowlyMutableVariant() {
      return new AutoValue_Types_SetType(BaseType.SET, this.parameterizedTypeArgs(), /*isMutable=*/true);
    }

    @Override
    public Optional<SetType> toDeeplyImmutableVariant() {
      // If any of the parameterized types can't be coerced to a deeply-immutable variant then this overall type
      // instance cannot be converted to something deeply-immutable **automatically** (really I'm just saying I
      // wouldn't be able to give a good suggestion).
      ImmutableMap.Builder<String, Type> deeplyImmutableParameterizedTypeVariantsBuilder = ImmutableMap.builder();
      for (Map.Entry<String, Type> paramTypeEntry : this.parameterizedTypeArgs().entrySet()) {
        Optional<? extends Type> elementType = Optional.of(paramTypeEntry.getValue());
        if (elementType.get() instanceof SupportsMutableVariant<?>) {
          elementType = ((SupportsMutableVariant<?>) elementType.get()).toDeeplyImmutableVariant();
        } else if (elementType.get() instanceof UserDefinedType) {
          elementType = ((UserDefinedType) elementType.get()).toDeeplyImmutableVariant();
        }
        if (elementType.isPresent()) {
          deeplyImmutableParameterizedTypeVariantsBuilder.put(paramTypeEntry.getKey(), elementType.get());
        } else {
          return Optional.empty();
        }
      }
      return Optional.of(
          new AutoValue_Types_SetType(
              BaseType.SET,
              deeplyImmutableParameterizedTypeVariantsBuilder.build(),
              /*isMutable=*/false
          ));
    }

    @Override
    public boolean isMutable() {
      return this.getIsMutable();
    }
  }

  @AutoValue
  public abstract static class TupleType extends Type implements Collection, SupportsMutableVariant<TupleType> {

    public abstract ImmutableList<Type> getValueTypes();

    public abstract boolean getIsMutable();

    public static TupleType forValueTypes(ImmutableList<Type> valueTypes) {
      return TupleType.forValueTypes(valueTypes, /*isMutable=*/false);
    }

    public static TupleType forValueTypes(ImmutableList<Type> valueTypes, boolean isMutable) {
      ImmutableMap.Builder<String, Type> parameterizedTypesMapBuilder = ImmutableMap.builder();
      for (int i = 0; i < valueTypes.size(); i++) {
        parameterizedTypesMapBuilder.put(String.format("$%s", i), valueTypes.get(i));
      }
      return new AutoValue_Types_TupleType(BaseType.TUPLE, parameterizedTypesMapBuilder.build(), valueTypes, isMutable);
    }

    @Override
    public Type getElementType() {
      // We literally have no way of determining this type at compile time without knowing which index is being
      // referenced so instead we'll mark this as UNDECIDED.
      return UNDECIDED;
    }

    @Override
    public String toString() {
      String baseFormattedType = String.format(
          this.baseType().getClaroCanonicalTypeNameFmtStr(),
          getValueTypes().stream().map(Type::toString).collect(Collectors.joining(", "))
      );
      return this.isMutable() ? "mut " + baseFormattedType : baseFormattedType;
    }

    @Override
    public String getJavaSourceClaroType() {
      return String.format(
          "Types.TupleType.forValueTypes(ImmutableList.of(%s), %s)",
          Joiner.on(", ")
              .join(this.getValueTypes()
                        .stream()
                        .map(Type::getJavaSourceClaroType)
                        .collect(ImmutableList.toImmutableList())),
          this.isMutable()
      );
    }

    @Override
    public TupleType toShallowlyMutableVariant() {
      return new AutoValue_Types_TupleType(BaseType.TUPLE, this.parameterizedTypeArgs(), this.getValueTypes(), /*isMutable=*/true);
    }

    @Override
    public Optional<TupleType> toDeeplyImmutableVariant() {
      // If any of the parameterized types can't be coerced to a deeply-immutable variant then this overall type
      // instance cannot be converted to something deeply-immutable **automatically** (really I'm just saying I
      // wouldn't be able to give a good suggestion).
      ImmutableMap.Builder<String, Type> deeplyImmutableParameterizedTypeVariantsBuilder = ImmutableMap.builder();
      for (Map.Entry<String, Type> paramTypeEntry : this.parameterizedTypeArgs().entrySet()) {
        Optional<? extends Type> elementType = Optional.of(paramTypeEntry.getValue());
        if (elementType.get() instanceof SupportsMutableVariant<?>) {
          elementType = ((SupportsMutableVariant<?>) elementType.get()).toDeeplyImmutableVariant();
        } else if (elementType.get() instanceof UserDefinedType) {
          elementType = ((UserDefinedType) elementType.get()).toDeeplyImmutableVariant();
        } else if (elementType.get() instanceof FutureType) {
          if (!Types.isDeeplyImmutable(
              elementType.get().parameterizedTypeArgs().get(FutureType.PARAMETERIZED_TYPE_KEY))) {
            // If it's not deeply immutable there's nothing that I can do to automatically make it deeply immutable
            // w/o the user manually doing a monadic(?) transform in a graph.
            elementType = Optional.empty();
          }
        }
        if (elementType.isPresent()) {
          deeplyImmutableParameterizedTypeVariantsBuilder.put(paramTypeEntry.getKey(), elementType.get());
        } else {
          return Optional.empty();
        }
      }
      return Optional.of(
          new AutoValue_Types_TupleType(
              BaseType.TUPLE,
              deeplyImmutableParameterizedTypeVariantsBuilder.build(),
              deeplyImmutableParameterizedTypeVariantsBuilder.build()
                  .values()
                  .stream()
                  .collect(ImmutableList.toImmutableList()),
              /*isMutable=*/false
          ));
    }

    @Override
    public boolean isMutable() {
      return this.getIsMutable();
    }
  }

  @AutoValue
  public abstract static class OneofType extends Type {

    public abstract ImmutableSet<Type> getVariantTypes();

    public static OneofType forVariantTypes(ImmutableList<Type> variants) {
      ImmutableSet<Type> variantTypesSet = ImmutableSet.copyOf(variants);
      if (variantTypesSet.size() < variants.size()) {
        // There was a duplicate type in this oneof variant list. This is an invalid instance.
        throw new RuntimeException(ClaroTypeException.forIllegalOneofTypeDeclarationWithDuplicatedTypes(variants, variantTypesSet));
      }
      return new AutoValue_Types_OneofType(BaseType.ONEOF, ImmutableMap.of(), variantTypesSet);
    }

    @Override
    public String toString() {
      return String.format(
          this.baseType().getClaroCanonicalTypeNameFmtStr(),
          this.getVariantTypes().stream().map(Type::toString).collect(Collectors.joining(", "))
      );
    }

    @Override
    public String getJavaSourceClaroType() {
      return String.format(
          "Types.OneofType.forVariantTypes(ImmutableList.of(%s))",
          this.getVariantTypes().stream().map(Type::getJavaSourceClaroType).collect(Collectors.joining(","))
      );
    }
  }

  @AutoValue
  public abstract static class StructType extends Type implements SupportsMutableVariant<StructType> {
    // Instead of using the parameterizedTypesMap I unfortunately have to explicitly list them separately as parallel
    // lists literally just so that the equals() and hashcode() impls correctly distinguish btwn field orderings which
    // ImmutableMap doesn't.
    public abstract ImmutableList<String> getFieldNames();

    public abstract ImmutableList<Type> getFieldTypes();

    abstract boolean getIsMutable();

    public static StructType forFieldTypes(ImmutableList<String> fieldNames, ImmutableList<Type> fieldTypes, boolean isMutable) {
      return new AutoValue_Types_StructType(BaseType.STRUCT, ImmutableMap.of(), fieldNames, fieldTypes, isMutable);
    }

    @Override
    public String toString() {
      String baseFormattedTypeStr =
          String.format(
              this.baseType().getClaroCanonicalTypeNameFmtStr(),
              IntStream.range(0, this.getFieldNames().size()).boxed()
                  .map(i ->
                           String.format("%s: %s", this.getFieldNames().get(i), this.getFieldTypes().get(i)))
                  .collect(Collectors.joining(", "))
          );
      return this.getIsMutable() ? "mut " + baseFormattedTypeStr : baseFormattedTypeStr;
    }

    @Override
    public String getJavaSourceClaroType() {
      return String.format(
          "Types.StructType.forFieldTypes(ImmutableList.of(%s), ImmutableList.of(%s), %s)",
          this.getFieldNames().stream().map(n -> String.format("\"%s\"", n)).collect(Collectors.joining(", ")),
          this.getFieldTypes().stream().map(Type::getJavaSourceClaroType).collect(Collectors.joining(", ")),
          this.getIsMutable()
      );
    }

    @Override
    public StructType toShallowlyMutableVariant() {
      return StructType.forFieldTypes(this.getFieldNames(), this.getFieldTypes(), /*isMutable=*/true);
    }

    @Override
    public Optional<StructType> toDeeplyImmutableVariant() {
      // If any of the parameterized types can't be coerced to a deeply-immutable variant then this overall type
      // instance cannot be converted to something deeply-immutable **automatically** (really I'm just saying I
      // wouldn't be able to give a good suggestion).
      ImmutableList.Builder<Type> deeplyImmutableParameterizedTypeVariantsBuilder = ImmutableList.builder();
      for (Map.Entry<String, Type> paramTypeEntry : this.parameterizedTypeArgs().entrySet()) {
        Optional<? extends Type> elementType = Optional.of(paramTypeEntry.getValue());
        if (elementType.get() instanceof SupportsMutableVariant<?>) {
          elementType = ((SupportsMutableVariant<?>) elementType.get()).toDeeplyImmutableVariant();
        } else if (elementType.get() instanceof UserDefinedType) {
          elementType = ((UserDefinedType) elementType.get()).toDeeplyImmutableVariant();
        } else if (elementType.get() instanceof FutureType) {
          if (!Types.isDeeplyImmutable(
              elementType.get().parameterizedTypeArgs().get(FutureType.PARAMETERIZED_TYPE_KEY))) {
            // If it's not deeply immutable there's nothing that I can do to automatically make it deeply immutable
            // w/o the user manually doing a monadic(?) transform in a graph.
            elementType = Optional.empty();
          }
        }
        if (elementType.isPresent()) {
          deeplyImmutableParameterizedTypeVariantsBuilder.add(elementType.get());
        } else {
          return Optional.empty();
        }
      }
      return Optional.of(
          StructType.forFieldTypes(
              this.getFieldNames(),
              deeplyImmutableParameterizedTypeVariantsBuilder.build(),
              /*isMutable=*/false
          ));
    }

    @Override
    public boolean isMutable() {
      return this.getIsMutable();
    }
  }

  public abstract static class ProcedureType extends Type {

    @Types.Nullable
    public abstract ImmutableList<Type> getArgTypes();

    @Types.Nullable
    public abstract Type getReturnType();

    // This field indicates whether this procedure is *annotated* blocking by the programmer (they may be wrong in which
    // case Claro will fail compilation to force this to be set correctly since Claro takes the philosophical stance
    // that truth on this important feature of a procedure needs to be communicated clearly).
    // For the sake of leveraging this field for blocking? as well, null == blocking?.
    @Types.Nullable
    public abstract Boolean getAnnotatedBlocking();

    // This field determines whether this procedure is generic over the blocking keyword, meaning that this type
    // definition is actually something like a template rather than a concrete type to type check against at the call
    // site. Instead, at the call-site, should check for an appropriate concrete type.
    public abstract Optional<ImmutableSet<Integer>> getAnnotatedBlockingGenericOverArgs();

    // In case this is a generic procedure, indicate the names of the generic args here.
    public abstract Optional<ImmutableList<String>> getGenericProcedureArgNames();

    // When comparing Types we don't ever want to care about *names* (or other metadata), these are meaningless to the
    // compiler and should be treated equivalently to a user comment in terms of the program's semantic execution. So
    // Make these fields *ignored* by AutoValue so that we can compare function type equality.
    // https://github.com/google/auto/blob/master/value/userguide/howto.md#ignore
    final AtomicReference<Boolean> autoValueIgnoredHasArgs = new AtomicReference<>();

    public boolean hasArgs() {
      return autoValueIgnoredHasArgs.get();
    }

    final AtomicReference<Boolean> autoValueIgnoredHasReturnValue = new AtomicReference<>();

    public boolean hasReturnValue() {
      return autoValueIgnoredHasReturnValue.get();
    }

    final AtomicReference<Optional<BaseType>> autoValueIgnoredOptionalOverrideBaseType =
        new AtomicReference<>(Optional.empty());

    public BaseType getPossiblyOverridenBaseType() {
      return this.autoValueIgnoredOptionalOverrideBaseType.get().orElse(this.baseType());
    }

    // ---------------------- BEGIN PROCEDURE ATTRIBUTES! --------------------- //
    // This field is mutable specifically because we need to be able to update this set first with the set
    // of keys that are directly depended on by this procedure, and then by the set of all of its transitive
    // deps. This is done in multiple phases, because we're parsing a DAG in linear order.
    final AtomicReference<HashSet<Key>> autoValueIgnoredUsedInjectedKeys = new AtomicReference<>();
    // This field is mutable specifically because we need to be able to update this mapping first with all
    // contract impls that are directly required by this procedure, and then by all of its transitively
    // required contract impls. This is done in multiple phases, because we're parsing a DAG in linear order.
    public final AtomicReference<ArrayListMultimap<String, ImmutableList<Type>>>
        allTransitivelyRequiredContractNamesToGenericArgs = new AtomicReference<>();
    // This field indicates whether this procedure is *actually* blocking based on whether a blocking operation is reachable.
    final AtomicReference<Boolean> autoValueIgnored_IsBlocking = new AtomicReference<>(false);
    // If this procedure is marked as blocking, this field *MAY* be populated to indicate that the blocking attribute
    // is the transitive result of a dep on a downstream blocking procedure.
    final AtomicReference<HashMap<String, Type>> autoValueIgnored_BlockingProcedureDeps =
        new AtomicReference<>(new HashMap<>());
    // This field indicates whether this procedure is a graph procedure.
    final AtomicReference<Boolean> autoValueIgnored_IsGraph = new AtomicReference<>(false);
    // ---------------------- END PROCEDURE ATTRIBUTES! --------------------- //

    public AtomicReference<Boolean> getIsBlocking() {
      return autoValueIgnored_IsBlocking;
    }

    public HashMap<String, Type> getBlockingProcedureDeps() {
      return autoValueIgnored_BlockingProcedureDeps.get();
    }

    public AtomicReference<Boolean> getIsGraph() {
      return autoValueIgnored_IsGraph;
    }

    public HashSet<Key> getUsedInjectedKeys() {
      return autoValueIgnoredUsedInjectedKeys.get();
    }

    public ArrayListMultimap<String, ImmutableList<Type>> getAllTransitivelyRequiredContractNamesToGenericArgs() {
      return allTransitivelyRequiredContractNamesToGenericArgs.get();
    }

    // We need a ref to the original ProcedureDefinitionStmt (or GenericFunctionDefinitionStmt in the case of a generic
    // procedure) for recursively asserting types to collect transitively used keys.
    final AtomicReference<Stmt> autoValueIgnoredProcedureDefStmt = new AtomicReference<>();

    public Stmt getProcedureDefStmt() {
      return autoValueIgnoredProcedureDefStmt.get();
    }

    public abstract String getJavaNewTypeDefinitionStmt(
        String procedureName, StringBuilder body, Optional<StringBuilder> optionalHelperMethods);

    public abstract String getJavaNewTypeDefinitionStmtForLambda(
        String procedureName, StringBuilder body, ImmutableMap<String, Type> capturedVariables);

    public String getStaticFunctionReferenceDefinitionStmt(String procedureName) {
      return String.format(
          baseType().getJavaNewTypeStaticPreambleFormatStr(),
          procedureName,
          procedureName,
          procedureName
      );
    }

    private static final Function<ImmutableList<Type>, String> collectToArgTypesListFormatFn =
        typesByNameMap ->
            typesByNameMap.size() > 1 ?
            typesByNameMap.stream()
                .map(Type::toString)
                .collect(Collectors.joining(", ", "|", "|")) :
            typesByNameMap.stream().findFirst().map(Type::toString).get();

    @Override
    @Nullable
    public ImmutableMap<String, Type> parameterizedTypeArgs() {
      // Internal Compiler Error: method parameterizedTypeArgs() would be ambiguous for Procedure Types, defer to
      // getReturnType() or getArgTypes() as applicable instead.
      return null;
    }

    @AutoValue
    public abstract static class FunctionType extends ProcedureType {

      public static FunctionType forArgsAndReturnTypes(
          ImmutableList<Type> argTypes,
          Type returnType,
          Set<Key> directUsedInjectedKeys,
          Stmt procedureDefinitionStmt,
          boolean explicitlyAnnotatedBlocking) {
        return FunctionType.forArgsAndReturnTypes(
            argTypes, returnType, BaseType.FUNCTION, directUsedInjectedKeys, procedureDefinitionStmt, explicitlyAnnotatedBlocking, Optional
                .empty());
      }

      // Factory method for a function that takes args and returns a value.
      public static FunctionType forArgsAndReturnTypes(
          ImmutableList<Type> argTypes,
          Type returnType,
          BaseType overrideBaseType,
          Set<Key> directUsedInjectedKeys,
          Stmt procedureDefinitionStmt,
          Boolean explicitlyAnnotatedBlocking,
          Optional<ImmutableSet<Integer>> genericBlockingOnArgs) {
        return FunctionType.forArgsAndReturnTypes(argTypes, returnType, overrideBaseType, directUsedInjectedKeys, procedureDefinitionStmt, explicitlyAnnotatedBlocking, genericBlockingOnArgs, Optional
            .empty(), Optional.empty());
      }

      // Factory method for a function that takes args and returns a value.
      public static FunctionType forArgsAndReturnTypes(
          ImmutableList<Type> argTypes,
          Type returnType,
          BaseType overrideBaseType,
          Set<Key> directUsedInjectedKeys,
          Stmt procedureDefinitionStmt,
          Boolean explicitlyAnnotatedBlocking,
          Optional<ImmutableSet<Integer>> genericBlockingOnArgs,
          Optional<ImmutableList<String>> optionalGenericProcedureArgNames,
          Optional<ImmutableListMultimap<String, ImmutableList<Type>>> optionalRequiredContractNamesToGenericArgs) {
        // Inheritance has gotten out of hand yet again.... FunctionType doesn't fit within the mold and won't have a
        // parameterizedTypeArgs map used
        FunctionType functionType = new AutoValue_Types_ProcedureType_FunctionType(
            BaseType.FUNCTION,
            argTypes,
            returnType,
            explicitlyAnnotatedBlocking,
            genericBlockingOnArgs,
            optionalGenericProcedureArgNames
        );

        functionType.autoValueIgnoredHasArgs.set(true);
        functionType.autoValueIgnoredHasReturnValue.set(true);
        functionType.autoValueIgnoredUsedInjectedKeys.set(Sets.newHashSet(directUsedInjectedKeys));
        functionType.autoValueIgnoredProcedureDefStmt.set(procedureDefinitionStmt);
        functionType.allTransitivelyRequiredContractNamesToGenericArgs.set(
            optionalRequiredContractNamesToGenericArgs.map(ArrayListMultimap::create).orElse(null));

        if (overrideBaseType != BaseType.FUNCTION) {
          functionType.autoValueIgnoredOptionalOverrideBaseType.set(Optional.of(overrideBaseType));
        } else {
          // Including this here for top-level function definitions instead of for lambdas since lambdas won't have
          // explicit blocking annotations (for convenience).
          functionType.getIsBlocking().set(explicitlyAnnotatedBlocking);
        }

        return functionType;
      }

      /**
       * This method exists SOLELY for representing type annotation literals provided in the source.
       */
      public static FunctionType typeLiteralForArgsAndReturnTypes(
          ImmutableList<Type> argTypes,
          Type returnType,
          Boolean explicitlyAnnotatedBlocking,
          Optional<ImmutableSet<Integer>> optionalAnnotatedBlockingGenericOverArgs,
          Optional<ImmutableList<String>> optionalGenericProcedureArgNames) {
        // Inheritance has gotten out of hand yet again.... FunctionType doesn't fit within the mold and won't have a
        // parameterizedTypeArgs map used
        FunctionType functionType = new AutoValue_Types_ProcedureType_FunctionType(
            BaseType.FUNCTION,
            argTypes,
            returnType,
            explicitlyAnnotatedBlocking,
            optionalAnnotatedBlockingGenericOverArgs,
            optionalGenericProcedureArgNames
        );

        functionType.autoValueIgnoredHasArgs.set(true);
        functionType.autoValueIgnoredHasReturnValue.set(true);
        // Including this here for top-level function definitions instead of for lambdas since lambdas won't have
        // explicit blocking annotations (for convenience).
        functionType.getIsBlocking().set(explicitlyAnnotatedBlocking);

        return functionType;
      }

      public static FunctionType typeLiteralForArgsAndReturnTypes(
          ImmutableList<Type> argTypes,
          Type returnType,
          Boolean explicitlyAnnotatedBlocking) {
        return typeLiteralForArgsAndReturnTypes(argTypes, returnType, explicitlyAnnotatedBlocking, Optional.empty(), Optional.empty());
      }

      @Override
      public String getJavaSourceType() {
        return String.format(
            this.autoValueIgnoredOptionalOverrideBaseType.get()
                .orElse(this.baseType())
                .getJavaSourceFmtStr(),
            this.getReturnType().getJavaSourceType()
        );
      }

      @Override
      public String getJavaNewTypeDefinitionStmt(
          String functionName, StringBuilder body, Optional<StringBuilder> optionalHelperMethods) {
        return String.format(
            this.autoValueIgnoredOptionalOverrideBaseType.get()
                .orElse(this.baseType())
                .getJavaNewTypeDefinitionStmtFmtStr(),
            functionName,
            getReturnType().getJavaSourceType(),
            getJavaSourceClaroType(),
            functionName,
            functionName,
            getReturnType().getJavaSourceType(),
            body,
            optionalHelperMethods.orElse(new StringBuilder()),
            this
        );
      }

      @Override
      public String getJavaNewTypeDefinitionStmtForLambda(String functionName, StringBuilder body, ImmutableMap<String, Type> capturedVariables) {
        return String.format(
            this.autoValueIgnoredOptionalOverrideBaseType.get()
                .orElse(this.baseType())
                .getJavaNewTypeDefinitionStmtFmtStr(),
            functionName,
            getReturnType().getJavaSourceType(),
            getJavaSourceClaroType(),
            functionName,
            functionName,
            capturedVariables.entrySet().stream()
                .map(e -> String.format("  private %s %s;\n", e.getValue().getJavaSourceType(), e.getKey()))
                .collect(Collectors.joining()),
            functionName,
            capturedVariables.entrySet().stream()
                .map(e -> String.format("%s %s", e.getValue().getJavaSourceType(), e.getKey()))
                .collect(Collectors.joining(", ")),
            capturedVariables.keySet().stream()
                .map(s -> String.format("    this.%s = %s;\n", s, s)).collect(Collectors.joining()),
            getReturnType().getJavaSourceType(),
            body,
            this,
            functionName,
            functionName,
            functionName,
            String.join(", ", capturedVariables.keySet())
        );
      }

      @Override
      public String toString() {
        StringBuilder fmtStr = new StringBuilder();
        if (this.autoValueIgnoredOptionalOverrideBaseType.get().isPresent()) {
          fmtStr.append(this.autoValueIgnoredOptionalOverrideBaseType.get().get().getClaroCanonicalTypeNameFmtStr());
        } else {
          if (this.getAnnotatedBlocking() == null) {
            fmtStr.append("blocking");
            fmtStr.append(
                this.getAnnotatedBlockingGenericOverArgs().isPresent()
                ? this.getAnnotatedBlockingGenericOverArgs().get().stream()
                    .map(i -> Integer.toString(i))
                    .collect(Collectors.joining("|", ":", " "))
                : "? ");
          } else {
            fmtStr.append(this.getAnnotatedBlocking() ? "blocking " : "");
          }
          fmtStr.append(this.baseType().getClaroCanonicalTypeNameFmtStr());
        }
        return String.format(
            fmtStr.toString(),
            collectToArgTypesListFormatFn.apply(this.getArgTypes()),
            this.getReturnType(),
            this.getGenericProcedureArgNames()
                .map(
                    genArgNames ->
                        genArgNames.stream()
                            // First convert the name to a $GenericTypeParam because the toString has been overridden.
                            .map(genArgName -> Types.$GenericTypeParam.forTypeParamName(genArgName).toString())
                            .collect(Collectors.joining(", ", " Generic Over {", "}")))
                .orElse("")
            + Optional.ofNullable(this.getAllTransitivelyRequiredContractNamesToGenericArgs())
                .map(requiredContracts ->
                         requiredContracts.entries().stream()
                             .map(entry ->
                                      String.format(
                                          "%s<%s>",
                                          entry.getKey(),
                                          entry.getValue()
                                              .stream()
                                              .map(Type::toString)
                                              .collect(Collectors.joining(", "))
                                      ))
                             .collect(Collectors.joining(", ", " Requiring Impls for Contracts {", "}")))
                .orElse("")
        );
      }

      @Override
      public String getJavaSourceClaroType() {
        return String.format(
            "Types.ProcedureType.FunctionType.typeLiteralForArgsAndReturnTypes(ImmutableList.<Type>of(%s), %s, %s)",
            this.getArgTypes().stream().map(Type::getJavaSourceClaroType).collect(Collectors.joining(", ")),
            this.getReturnType().getJavaSourceClaroType(),
            this.getIsBlocking().get()
        );
      }
    }

    @AutoValue
    public abstract static class ProviderType extends ProcedureType {
      public static ProviderType forReturnType(
          Type returnType,
          Set<Key> directUsedInjectedKeys,
          Stmt procedureDefinitionStmt,
          boolean explicitlyAnnotatedBlocking) {
        return ProviderType.forReturnType(
            returnType, BaseType.PROVIDER_FUNCTION, directUsedInjectedKeys, procedureDefinitionStmt, explicitlyAnnotatedBlocking, Optional.empty(), Optional.empty());
      }

      public static ProviderType forReturnType(
          Type returnType,
          BaseType overrideBaseType,
          Set<Key> directUsedInjectedKeys,
          Stmt procedureDefinitionStmt,
          Boolean explicitlyAnnotatedBlocking,
          Optional<ImmutableList<String>> optionalGenericProcedureArgNames,
          Optional<ImmutableListMultimap<String, ImmutableList<Type>>> optionalRequiredContractNamesToGenericArgs) {
        ProviderType providerType = new AutoValue_Types_ProcedureType_ProviderType(
            BaseType.PROVIDER_FUNCTION,
            ImmutableList.of(),
            returnType,
            explicitlyAnnotatedBlocking,
            Optional.empty(),
            optionalGenericProcedureArgNames
        );

        providerType.autoValueIgnoredHasArgs.set(false);
        providerType.autoValueIgnoredHasReturnValue.set(true);
        providerType.autoValueIgnoredUsedInjectedKeys.set(Sets.newHashSet(directUsedInjectedKeys));
        providerType.autoValueIgnoredProcedureDefStmt.set(procedureDefinitionStmt);
        providerType.allTransitivelyRequiredContractNamesToGenericArgs.set(
            optionalRequiredContractNamesToGenericArgs.map(ArrayListMultimap::create).orElse(null));

        if (overrideBaseType != BaseType.PROVIDER_FUNCTION) {
          providerType.autoValueIgnoredOptionalOverrideBaseType.set(Optional.of(overrideBaseType));
        } else {
          providerType.getIsBlocking().set(explicitlyAnnotatedBlocking);
        }

        return providerType;
      }

      public static ProviderType typeLiteralForReturnType(Type returnType, Boolean explicitlyAnnotatedBlocking) {
        ProviderType providerType = new AutoValue_Types_ProcedureType_ProviderType(
            BaseType.PROVIDER_FUNCTION,
            ImmutableList.of(),
            returnType,
            explicitlyAnnotatedBlocking,
            Optional.empty(),
            Optional.empty()
        );

        providerType.autoValueIgnoredHasArgs.set(false);
        providerType.autoValueIgnoredHasReturnValue.set(true);
        providerType.getIsBlocking().set(explicitlyAnnotatedBlocking);

        return providerType;
      }

      @Override
      public String getJavaSourceType() {
        return String.format(
            this.autoValueIgnoredOptionalOverrideBaseType.get()
                .orElse(this.baseType())
                .getJavaSourceFmtStr(),
            this.getReturnType().getJavaSourceType()
        );
      }

      @Override
      public String getJavaNewTypeDefinitionStmt(
          String providerName, StringBuilder body, Optional<StringBuilder> optionalHelperMethods) {
        String returnTypeJavaSource = getReturnType().getJavaSourceType();
        return String.format(
            this.autoValueIgnoredOptionalOverrideBaseType.get()
                .orElse(this.baseType())
                .getJavaNewTypeDefinitionStmtFmtStr(),
            providerName,
            returnTypeJavaSource,
            getJavaSourceClaroType(),
            providerName,
            providerName,
            returnTypeJavaSource,
            body,
            optionalHelperMethods.orElse(new StringBuilder()),
            this,
            providerName,
            providerName,
            providerName,
            providerName
        );
      }

      @Override
      public String getJavaNewTypeDefinitionStmtForLambda(
          String providerName, StringBuilder body, ImmutableMap<String, Type> capturedVariables) {
        String returnTypeJavaSource = getReturnType().getJavaSourceType();
        return String.format(
            this.autoValueIgnoredOptionalOverrideBaseType.get()
                .get()
                .getJavaNewTypeDefinitionStmtFmtStr(),
            providerName,
            returnTypeJavaSource,
            getJavaSourceClaroType(),
            providerName,
            providerName,
            capturedVariables.entrySet().stream()
                .map(e -> String.format("  private %s %s;\n", e.getValue().getJavaSourceType(), e.getKey()))
                .collect(Collectors.joining()),
            providerName,
            capturedVariables.entrySet().stream()
                .map(e -> String.format("%s %s", e.getValue().getJavaSourceType(), e.getKey()))
                .collect(Collectors.joining(", ")),
            capturedVariables.keySet().stream()
                .map(s -> String.format("    this.%s = %s;\n", s, s)).collect(Collectors.joining()),
            returnTypeJavaSource,
            body,
            this,
            providerName,
            providerName,
            providerName,
            String.join(", ", capturedVariables.keySet())
        );
      }

      @Override
      public String toString() {
        StringBuilder fmtStr = new StringBuilder();
        if (this.autoValueIgnoredOptionalOverrideBaseType.get().isPresent()) {
          fmtStr.append(this.autoValueIgnoredOptionalOverrideBaseType.get().get().getClaroCanonicalTypeNameFmtStr());
        } else {
          if (this.getAnnotatedBlocking() == null) {
            fmtStr.append("blocking");
            fmtStr.append(
                this.getAnnotatedBlockingGenericOverArgs().isPresent()
                ? this.getAnnotatedBlockingGenericOverArgs().get().stream()
                    .map(i -> Integer.toString(i))
                    .collect(Collectors.joining("|", ":", " "))
                : "? ");
          } else {
            fmtStr.append(this.getAnnotatedBlocking() ? "blocking " : "");
          }
          fmtStr.append(this.baseType().getClaroCanonicalTypeNameFmtStr());
        }
        return String.format(
            fmtStr.toString(),
            this.getReturnType()
        );
      }

      @Override
      public String getJavaSourceClaroType() {
        return String.format(
            "Types.ProcedureType.ProviderType.typeLiteralForReturnType(%s, %s)",
            this.getReturnType().getJavaSourceClaroType(),
            this.getIsBlocking().get()
        );
      }
    }

    @AutoValue
    public abstract static class ConsumerType extends ProcedureType {

      @Override
      @Nullable
      public Type getReturnType() {
        // Internal Compiler Error: Consumers do not have a return value, calling getReturnType() is invalid.
        return null;
      }

      public static ConsumerType forConsumerArgTypes(
          ImmutableList<Type> argTypes,
          Set<Key> directUsedInjectedKeys,
          Stmt procedureDefinitionStmt,
          boolean explicitlyAnnotatedBlocking) {
        return ConsumerType.forConsumerArgTypes(
            argTypes, BaseType.CONSUMER_FUNCTION, directUsedInjectedKeys, procedureDefinitionStmt, explicitlyAnnotatedBlocking, Optional
                .empty());
      }

      public static ConsumerType forConsumerArgTypes(
          ImmutableList<Type> argTypes,
          BaseType overrideBaseType,
          Set<Key> directUsedInjectedKeys,
          Stmt procedureDefinitionStmt,
          Boolean explicitlyAnnotatedBlocking,
          Optional<ImmutableSet<Integer>> genericBlockingOnArgs) {
        ConsumerType consumerType = new AutoValue_Types_ProcedureType_ConsumerType(
            BaseType.CONSUMER_FUNCTION,
            argTypes,
            explicitlyAnnotatedBlocking,
            genericBlockingOnArgs,
            Optional.empty() // TODO(steving) Implement generic consumer functions.
        );

        consumerType.autoValueIgnoredHasArgs.set(true);
        consumerType.autoValueIgnoredHasReturnValue.set(false);
        consumerType.autoValueIgnoredUsedInjectedKeys.set(Sets.newHashSet(directUsedInjectedKeys));
        consumerType.autoValueIgnoredProcedureDefStmt.set(procedureDefinitionStmt);

        if (overrideBaseType != BaseType.CONSUMER_FUNCTION) {
          consumerType.autoValueIgnoredOptionalOverrideBaseType.set(Optional.of(overrideBaseType));
        } else {
          consumerType.getIsBlocking().set(explicitlyAnnotatedBlocking);
        }

        return consumerType;
      }

      public static ConsumerType forConsumerArgTypes(
          ImmutableList<Type> argTypes,
          BaseType overrideBaseType,
          Set<Key> directUsedInjectedKeys,
          Stmt procedureDefinitionStmt,
          Boolean explicitlyAnnotatedBlocking,
          Optional<ImmutableSet<Integer>> genericBlockingOnArgs,
          Optional<ImmutableList<String>> optionalGenericProcedureArgNames,
          Optional<ImmutableListMultimap<String, ImmutableList<Type>>> optionalRequiredContractNamesToGenericArgs) {
        ConsumerType consumerType = new AutoValue_Types_ProcedureType_ConsumerType(
            BaseType.CONSUMER_FUNCTION,
            argTypes,
            explicitlyAnnotatedBlocking,
            genericBlockingOnArgs,
            optionalGenericProcedureArgNames
        );

        consumerType.autoValueIgnoredHasArgs.set(true);
        consumerType.autoValueIgnoredHasReturnValue.set(false);
        consumerType.autoValueIgnoredUsedInjectedKeys.set(Sets.newHashSet(directUsedInjectedKeys));
        consumerType.autoValueIgnoredProcedureDefStmt.set(procedureDefinitionStmt);
        consumerType.allTransitivelyRequiredContractNamesToGenericArgs.set(
            optionalRequiredContractNamesToGenericArgs.map(ArrayListMultimap::create).orElse(null));

        if (overrideBaseType != BaseType.CONSUMER_FUNCTION) {
          consumerType.autoValueIgnoredOptionalOverrideBaseType.set(Optional.of(overrideBaseType));
        } else {
          consumerType.getIsBlocking().set(explicitlyAnnotatedBlocking);
        }

        return consumerType;
      }

      public static ConsumerType typeLiteralForConsumerArgTypes(
          ImmutableList<Type> argTypes,
          Boolean explicitlyAnnotatedBlocking,
          Optional<ImmutableSet<Integer>> optionalAnnotatedBlockingGenericOverArgs,
          Optional<ImmutableList<String>> optionalGenericProcedureArgNames) {
        ConsumerType consumerType = new AutoValue_Types_ProcedureType_ConsumerType(
            BaseType.CONSUMER_FUNCTION,
            argTypes,
            explicitlyAnnotatedBlocking,
            optionalAnnotatedBlockingGenericOverArgs,
            optionalGenericProcedureArgNames
        );

        consumerType.autoValueIgnoredHasArgs.set(true);
        consumerType.autoValueIgnoredHasReturnValue.set(false);
        consumerType.getIsBlocking().set(explicitlyAnnotatedBlocking);

        return consumerType;
      }

      public static ConsumerType typeLiteralForConsumerArgTypes(
          ImmutableList<Type> argTypes,
          Boolean explicitlyAnnotatedBlocking) {
        return typeLiteralForConsumerArgTypes(argTypes, explicitlyAnnotatedBlocking, Optional.empty(), Optional.empty());
      }

      @Override
      public String getJavaSourceType() {
        return this.autoValueIgnoredOptionalOverrideBaseType.get()
            .orElse(this.baseType())
            .getJavaSourceFmtStr();
      }

      @Override
      public String getJavaNewTypeDefinitionStmt(
          String consumerName, StringBuilder body, Optional<StringBuilder> optionalHelperMethods) {
        return String.format(
            this.autoValueIgnoredOptionalOverrideBaseType.get()
                .orElse(this.baseType())
                .getJavaNewTypeDefinitionStmtFmtStr(),
            consumerName,
            getJavaSourceClaroType(),
            consumerName,
            consumerName,
            body,
            optionalHelperMethods.orElse(new StringBuilder()),
            this,
            consumerName,
            consumerName,
            consumerName,
            consumerName
        );
      }

      @Override
      public String getJavaNewTypeDefinitionStmtForLambda(
          String consumerName, StringBuilder body, ImmutableMap<String, Type> capturedVariables) {
        return String.format(
            this.autoValueIgnoredOptionalOverrideBaseType.get().get().getJavaNewTypeDefinitionStmtFmtStr(),
            consumerName,
            getJavaSourceClaroType(),
            consumerName,
            consumerName,
            capturedVariables.entrySet().stream()
                .map(e -> String.format("  private %s %s;\n", e.getValue().getJavaSourceType(), e.getKey()))
                .collect(Collectors.joining()),
            consumerName,
            capturedVariables.entrySet().stream()
                .map(e -> String.format("%s %s", e.getValue().getJavaSourceType(), e.getKey()))
                .collect(Collectors.joining(", ")),
            capturedVariables.keySet().stream()
                .map(s -> String.format("    this.%s = %s;\n", s, s)).collect(Collectors.joining()),
            body,
            this.toString(),
            consumerName,
            consumerName,
            consumerName,
            String.join(", ", capturedVariables.keySet())
        );
      }

      @Override
      public String toString() {
        StringBuilder fmtStr = new StringBuilder();
        if (this.autoValueIgnoredOptionalOverrideBaseType.get().isPresent()) {
          fmtStr.append(this.autoValueIgnoredOptionalOverrideBaseType.get().get().getClaroCanonicalTypeNameFmtStr());
        } else {
          if (this.getAnnotatedBlocking() == null) {
            fmtStr.append("blocking");
            fmtStr.append(
                this.getAnnotatedBlockingGenericOverArgs().isPresent()
                ? this.getAnnotatedBlockingGenericOverArgs().get().stream()
                    .map(i -> Integer.toString(i))
                    .collect(Collectors.joining("|", ":", " "))
                : "? ");
          } else {
            fmtStr.append(this.getAnnotatedBlocking() ? "blocking " : "");
          }
          fmtStr.append(this.baseType().getClaroCanonicalTypeNameFmtStr());
        }
        return String.format(
            fmtStr.toString(),
            collectToArgTypesListFormatFn.apply(this.getArgTypes())
        );
      }

      @Override
      public String getJavaSourceClaroType() {
        return String.format(
            "Types.ProcedureType.ConsumerType.typeLiteralForConsumerArgTypes(ImmutableList.<Type>of(%s), %s)",
            this.getArgTypes().stream().map(Type::getJavaSourceClaroType).collect(Collectors.joining(", ")),
            this.getIsBlocking().get()
        );
      }
    }

    public abstract class ProcedureWrapper {
      // This is a little ridiculous, but the type safety will have to be managed exclusively by the Compiler's type
      // checking system. Stress test... Oh le do it. The given ScopedHeap is likely already the same one as given at
      // the function's definition time, but honestly just in case some weird scoping jiu-jitsu has to happen later this
      // is safer to pass in whatever ScopedHeap is necessary at call-time.
      public abstract Object apply(ImmutableList<Expr> args, ScopedHeap scopedHeap);

      public Object apply(ScopedHeap scopedHeap) {
        return apply(ImmutableList.of(), scopedHeap);
      }

      @Override
      public String toString() {
        return ProcedureType.this.toString();
      }
    }

  }

  @AutoValue
  public abstract static class FutureType extends Type {
    public static final String PARAMETERIZED_TYPE_KEY = "$value";

    public static FutureType wrapping(Type valueType) {
      return new AutoValue_Types_FutureType(BaseType.FUTURE, ImmutableMap.of(PARAMETERIZED_TYPE_KEY, valueType));
    }

    @Override
    public String getJavaSourceClaroType() {
      return String.format(
          "Types.FutureType.wrapping(%s)",
          this.parameterizedTypeArgs().get(PARAMETERIZED_TYPE_KEY).getJavaSourceClaroType()
      );
    }
  }

  @AutoValue
  public abstract static class UserDefinedType extends Type {
    public static final HashMap<String, Type> $resolvedWrappedTypes = Maps.newHashMap();
    public static final HashMap<String, ImmutableList<String>> $typeParamNames = Maps.newHashMap();

    public abstract String getTypeName();

    public static UserDefinedType forTypeName(String typeName) {
      return new AutoValue_Types_UserDefinedType(BaseType.USER_DEFINED_TYPE, ImmutableMap.of(), typeName);
    }

    public static UserDefinedType forTypeNameAndParameterizedTypes(
        String typeName, ImmutableList<Type> parameterizedTypes) {
      return new AutoValue_Types_UserDefinedType(
          BaseType.USER_DEFINED_TYPE,
          IntStream.range(0, parameterizedTypes.size()).boxed()
              .collect(ImmutableMap.<Integer, String, Type>toImmutableMap(Object::toString, parameterizedTypes::get)),
          typeName
      );
    }

    @Override
    public String getJavaSourceClaroType() {
      if (this.parameterizedTypeArgs().isEmpty()) {
        return String.format(
            "Types.UserDefinedType.forTypeName(\"%s\")",
            this.getTypeName()
        );
      }
      return String.format(
          "Types.UserDefinedType.forTypeNameAndParameterizedTypes(\"%s\", ImmutableList.of(%s))",
          this.getTypeName(),
          this.parameterizedTypeArgs()
              .values()
              .stream()
              .map(Type::getJavaSourceClaroType)
              .collect(Collectors.joining(", "))
      );
    }

    @Override
    public String getJavaSourceType() {
      // Setup $GenericTypeParam to defer codegen to the concrete types for this instance if this one is parameterized.
      Optional<Map<Type, Type>> originalGenTypeCodegenMappings
          = $GenericTypeParam.concreteTypeMappingsForParameterizedTypeCodegen;
      if (!this.parameterizedTypeArgs().isEmpty()) {
        ImmutableList<String> typeParamNames = UserDefinedType.$typeParamNames.get(this.getTypeName());
        $GenericTypeParam.concreteTypeMappingsForParameterizedTypeCodegen =
            Optional.of(
                IntStream.range(0, this.parameterizedTypeArgs().size()).boxed()
                    .collect(ImmutableMap.toImmutableMap(
                        i -> $GenericTypeParam.forTypeParamName(typeParamNames.get(i)),
                        i -> this.parameterizedTypeArgs().get(i.toString())
                    )));
      }

      // Actual codegen.
      String res = String.format(
          this.baseType().getJavaSourceFmtStr(),
          UserDefinedType.$resolvedWrappedTypes.get(this.getTypeName()).getJavaSourceType()
      );

      // Reset state.
      if (!this.parameterizedTypeArgs().isEmpty()) {
        $GenericTypeParam.concreteTypeMappingsForParameterizedTypeCodegen = originalGenTypeCodegenMappings;
      }
      return res;
    }

    @Override
    public String toString() {
      if (this.parameterizedTypeArgs().isEmpty()) {
        return this.getTypeName();
      }
      return String.format(
          "%s<%s>",
          this.getTypeName(),
          this.parameterizedTypeArgs().values().stream().map(Type::toString).collect(Collectors.joining(", "))
      );
    }

    // It's not always the case that all arbitrary user-defined types have a natural deeply-immutable variant. In
    // particular, if the wrapped type itself already contains an explicit `mut` annotation, then it's impossible
    // to construct any instance of this type that is deeply-immutable.
    public Optional<UserDefinedType> toDeeplyImmutableVariant() {
      if (!Types.isDeeplyImmutable(UserDefinedType.$resolvedWrappedTypes.get(getTypeName()))) {
        return Optional.empty();
      }
      // If any of the parameterized types can't be coerced to a deeply-immutable variant then this overall type
      // instance cannot be converted to something deeply-immutable **automatically** (really I'm just saying I
      // wouldn't be able to give a good suggestion).
      ImmutableMap.Builder<String, Type> deeplyImmutableParameterizedTypeVariantsBuilder = ImmutableMap.builder();
      for (Map.Entry<String, Type> paramTypeEntry : this.parameterizedTypeArgs().entrySet()) {
        Optional<? extends Type> elementType = Optional.of(paramTypeEntry.getValue());
        if (elementType.get() instanceof SupportsMutableVariant<?>) {
          elementType = ((SupportsMutableVariant<?>) elementType.get()).toDeeplyImmutableVariant();
        } else if (elementType.get() instanceof UserDefinedType) {
          elementType = ((UserDefinedType) elementType.get()).toDeeplyImmutableVariant();
        } else if (elementType.get() instanceof FutureType) {
          if (!Types.isDeeplyImmutable(
              elementType.get().parameterizedTypeArgs().get(FutureType.PARAMETERIZED_TYPE_KEY))) {
            // If it's not deeply immutable there's nothing that I can do to automatically make it deeply immutable
            // w/o the user manually doing a monadic(?) transform in a graph.
            elementType = Optional.empty();
          }
        }
        if (elementType.isPresent()) {
          deeplyImmutableParameterizedTypeVariantsBuilder.put(paramTypeEntry.getKey(), elementType.get());
        } else {
          return Optional.empty();
        }
      }
      return Optional.of(
          new AutoValue_Types_UserDefinedType(
              BaseType.USER_DEFINED_TYPE,
              deeplyImmutableParameterizedTypeVariantsBuilder.build(),
              this.getTypeName()
          ));
    }

  }

  // This is really a meta-type which exists primarily to allow some mechanism of holding the name of a generic
  // type param in the scopedheap. This should see very limited use throughout the compiler internals only and
  // none at all by Claro programs.
  @AutoValue
  public abstract static class $GenericTypeParam extends Type {
    public static Optional<Map<Type, Type>> concreteTypeMappingsForBetterErrorMessages = Optional.empty();
    public static Optional<Map<Type, Type>> concreteTypeMappingsForParameterizedTypeCodegen = Optional.empty();

    public abstract String getTypeParamName();

    public static $GenericTypeParam forTypeParamName(String name) {
      return new AutoValue_Types_$GenericTypeParam(BaseType.$GENERIC_TYPE_PARAM, ImmutableMap.of(), name);
    }

    @Override
    public String getJavaSourceClaroType() {
      // In the case that we're doing codegen for a parameterized type, we can conveniently redirect to the concrete
      // types' codegen so that we don't need to do structural pattern matching just for codegen.
      if ($GenericTypeParam.concreteTypeMappingsForParameterizedTypeCodegen.isPresent()) {
        return concreteTypeMappingsForParameterizedTypeCodegen.get().get(this).getJavaSourceClaroType();
      }
      throw new ClaroParserException("Internal Compiler Error: This type should be unreachable in Claro programs.");
    }

    @Override
    public String getJavaSourceType() {
      // In the case that we're doing codegen for a parameterized type, we can conveniently redirect to the concrete
      // types' codegen so that we don't need to do structural pattern matching just for codegen.
      if ($GenericTypeParam.concreteTypeMappingsForParameterizedTypeCodegen.isPresent()) {
        return concreteTypeMappingsForParameterizedTypeCodegen.get().get(this).getJavaSourceType();
      }
      // Effectively this should trigger a runtime exception.
      return super.getJavaSourceType();
    }

    @Override
    public String toString() {
      return String.format(
          this.baseType().getClaroCanonicalTypeNameFmtStr(),
          concreteTypeMappingsForBetterErrorMessages.map(
                  mapping -> {
                    Type mappedType = mapping.getOrDefault(this, this);
                    if (mappedType.equals(this)) {
                      return this.getTypeParamName();
                    }
                    return mappedType.toString();
                  })
              .orElse(this.getTypeParamName())
      );
    }
  }

  // This is really a meta-type which exists primarily to allow some mechanism of holding the name of a generic
  // type param in the scopedheap. This should see very limited use throughout the compiler internals only and
  // none at all by Claro programs.
  @AutoValue
  public abstract static class $Contract extends Type {
    public abstract String getContractName();

    public abstract ImmutableList<String> getTypeParamNames();

    public abstract ImmutableList<String> getProcedureNames();

    public static $Contract forContractNameTypeParamNamesAndProcedureNames(
        String name, ImmutableList<String> typeParamNames, ImmutableList<String> procedureNames) {
      return new AutoValue_Types_$Contract(BaseType.$CONTRACT, ImmutableMap.of(), name, typeParamNames, procedureNames);
    }

    @Override
    public String getJavaSourceClaroType() {
      throw new ClaroParserException("Internal Compiler Error: This type should be unreachable in Claro programs.");
    }

    @Override
    public String toString() {
      return String.format(
          baseType().getClaroCanonicalTypeNameFmtStr(),
          getContractName(),
          String.join(", ", getTypeParamNames())
      );
    }
  }

  @AutoValue
  public abstract static class $ContractImplementation extends Type {
    public abstract String getContractName();

    public abstract ImmutableList<Type> getConcreteTypeParams();

    public static $ContractImplementation forContractNameAndConcreteTypeParams(
        String name, ImmutableList<Type> concreteTypeParams) {
      return new AutoValue_Types_$ContractImplementation(
          BaseType.$CONTRACT_IMPLEMENTATION, ImmutableMap.of(), name, concreteTypeParams);
    }

    @Override
    public String getJavaSourceClaroType() {
      throw new ClaroParserException("Internal Compiler Error: This type should be unreachable in Claro programs.");
    }
  }


  // This is gonna be used to convey to AutoValue that certain values are nullable and it will generate null-friendly
  // constructors and .equals() and .hashCode() methods.
  // https://github.com/google/auto/blob/master/value/userguide/howto.md#nullable
  @interface Nullable {
  }

  public static boolean isDeeplyImmutable(Type type) {
    if (type instanceof SupportsMutableVariant<?>) {
      // Quickly, if this outer layer is mutable, then we already know the overall type is not *deeply* immutable.
      if (((SupportsMutableVariant<?>) type).isMutable()) {
        return false;
      }
      // So now, whether the type is deeply-immutable or not strictly depends on the parameterized types.
      switch (type.baseType()) {
        case LIST:
          return isDeeplyImmutable(((ListType) type).getElementType());
        case SET:
          return isDeeplyImmutable(type.parameterizedTypeArgs().get(SetType.PARAMETERIZED_TYPE));
        case MAP:
          return isDeeplyImmutable(type.parameterizedTypeArgs().get(MapType.PARAMETERIZED_TYPE_KEYS))
                 && isDeeplyImmutable(type.parameterizedTypeArgs().get(MapType.PARAMETERIZED_TYPE_VALUES));
        case TUPLE:
          return type.parameterizedTypeArgs().values().stream()
              .allMatch(Types::isDeeplyImmutable);
        case STRUCT:
          return ((StructType) type).getFieldTypes().stream()
              .allMatch(Types::isDeeplyImmutable);
        default:
          throw new RuntimeException("Internal Compiler Error: Unsupported structured type found in isDeeplyImmutable()!");
      }
    } else if (type.baseType().equals(BaseType.USER_DEFINED_TYPE)) {
      // User defined types are inherently shallow-ly immutable, so whether they're deeply-immutable simply depends on
      // recursing into the wrapped type. If the wrapped type is deeply-immutable, then it also depends on the
      // mutability of any parameterized types.
      return isDeeplyImmutable(
          UserDefinedType.$resolvedWrappedTypes.get(((UserDefinedType) type).getTypeName()))
             && type.parameterizedTypeArgs().values().stream().allMatch(Types::isDeeplyImmutable);
    } else if (type.baseType().equals(BaseType.FUTURE)) {
      // Futures are inherently shallow-ly immutable, so whether they're deeply-immutable simply depends on recursing
      // into the wrapped type.
      return isDeeplyImmutable(type.parameterizedTypeArgs().get(FutureType.PARAMETERIZED_TYPE_KEY));
    } else {
      return true;
    }
  }

  public static Optional<? extends Type> getDeeplyImmutableVariantTypeRecommendationForError(Type type) {
    switch (type.baseType()) {
      case LIST:
      case SET:
      case MAP:
      case TUPLE:
      case STRUCT:
        return ((SupportsMutableVariant<?>) type).toDeeplyImmutableVariant();
      case USER_DEFINED_TYPE:
        return ((UserDefinedType) type).toDeeplyImmutableVariant();
      case FUTURE:
        // Future can't itself support a toDeeplyImmutableVariant() method because there's actually no such conversion
        // that's *actually* valid in Claro semantics. Here we're doing this *only in the context of providing a nice
        // recommendation to the user once an error has already been identified*.
        Optional<? extends Type> optionalWrappedDeeplyImmutableVariantType =
            getDeeplyImmutableVariantTypeRecommendationForError(
                type.parameterizedTypeArgs().get(FutureType.PARAMETERIZED_TYPE_KEY));
        if (!optionalWrappedDeeplyImmutableVariantType.isPresent()) {
          return Optional.empty();
        }
        return Optional.of(FutureType.wrapping(optionalWrappedDeeplyImmutableVariantType.get()));
      default: // Everything else should already be inherently immutable.
        return Optional.of(type);
    }
  }
}
