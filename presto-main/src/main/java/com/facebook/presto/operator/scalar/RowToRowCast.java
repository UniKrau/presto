/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.operator.scalar;

import com.facebook.presto.bytecode.BytecodeBlock;
import com.facebook.presto.bytecode.ClassDefinition;
import com.facebook.presto.bytecode.CompilerUtils;
import com.facebook.presto.bytecode.MethodDefinition;
import com.facebook.presto.bytecode.Parameter;
import com.facebook.presto.bytecode.Scope;
import com.facebook.presto.bytecode.Variable;
import com.facebook.presto.bytecode.control.IfStatement;
import com.facebook.presto.bytecode.expression.BytecodeExpression;
import com.facebook.presto.metadata.BoundVariables;
import com.facebook.presto.metadata.FunctionRegistry;
import com.facebook.presto.metadata.Signature;
import com.facebook.presto.metadata.SqlOperator;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.StandardErrorCode;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.block.BlockBuilderStatus;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.TypeManager;
import com.facebook.presto.sql.gen.CachedInstanceBinder;
import com.facebook.presto.sql.gen.CallSiteBinder;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;

import java.lang.invoke.MethodHandle;
import java.util.List;

import static com.facebook.presto.bytecode.Access.FINAL;
import static com.facebook.presto.bytecode.Access.PUBLIC;
import static com.facebook.presto.bytecode.Access.STATIC;
import static com.facebook.presto.bytecode.Access.a;
import static com.facebook.presto.bytecode.CompilerUtils.defineClass;
import static com.facebook.presto.bytecode.Parameter.arg;
import static com.facebook.presto.bytecode.ParameterizedType.type;
import static com.facebook.presto.bytecode.expression.BytecodeExpressions.constantBoolean;
import static com.facebook.presto.bytecode.expression.BytecodeExpressions.constantInt;
import static com.facebook.presto.bytecode.expression.BytecodeExpressions.newInstance;
import static com.facebook.presto.metadata.Signature.internalOperator;
import static com.facebook.presto.metadata.Signature.withVariadicBound;
import static com.facebook.presto.operator.scalar.ScalarFunctionImplementation.ArgumentProperty.valueTypeArgumentProperty;
import static com.facebook.presto.operator.scalar.ScalarFunctionImplementation.NullConvention.RETURN_NULL_ON_NULL;
import static com.facebook.presto.spi.function.OperatorType.CAST;
import static com.facebook.presto.spi.type.TypeSignature.parseTypeSignature;
import static com.facebook.presto.sql.gen.InvokeFunctionBytecodeExpression.invokeFunction;
import static com.facebook.presto.sql.gen.SqlTypeBytecodeExpression.constantType;
import static com.facebook.presto.type.UnknownType.UNKNOWN;
import static com.facebook.presto.util.Reflection.methodHandle;
import static com.google.common.base.Preconditions.checkArgument;

public class RowToRowCast
        extends SqlOperator
{
    public static final RowToRowCast ROW_TO_ROW_CAST = new RowToRowCast();

    private RowToRowCast()
    {
        super(CAST, ImmutableList.of(withVariadicBound("F", "row"), withVariadicBound("T", "row")), ImmutableList.of(), parseTypeSignature("T"), ImmutableList.of(parseTypeSignature("F")));
    }

    @Override
    public ScalarFunctionImplementation specialize(BoundVariables boundVariables, int arity, TypeManager typeManager, FunctionRegistry functionRegistry)
    {
        checkArgument(arity == 1, "Expected arity to be 1");
        Type fromType = boundVariables.getTypeVariable("F");
        Type toType = boundVariables.getTypeVariable("T");
        if (fromType.getTypeParameters().size() != toType.getTypeParameters().size()) {
            throw new PrestoException(StandardErrorCode.INVALID_FUNCTION_ARGUMENT, "the size of fromType and toType must match");
        }
        Class<?> castOperatorClass = generateRowCast(fromType, toType, functionRegistry);
        MethodHandle methodHandle = methodHandle(castOperatorClass, "castRow", ConnectorSession.class, Block.class);
        return new ScalarFunctionImplementation(
                false,
                ImmutableList.of(valueTypeArgumentProperty(RETURN_NULL_ON_NULL)),
                methodHandle,
                isDeterministic());
    }

    private static Class<?> generateRowCast(Type fromType, Type toType, FunctionRegistry functionRegistry)
    {
        List<Type> toTypes = toType.getTypeParameters();
        List<Type> fromTypes = fromType.getTypeParameters();

        CallSiteBinder binder = new CallSiteBinder();

        // Embed the MD5 hash code of input and output types into the generated class name instead of the raw type names,
        // which could prevent the class name from hitting the length limitation and invalid characters.
        byte[] md5Suffix = Hashing.md5().hashBytes((fromType + "$" + toType).getBytes()).asBytes();

        ClassDefinition definition = new ClassDefinition(
                a(PUBLIC, FINAL),
                CompilerUtils.makeClassName(Joiner.on("$").join("RowCast", BaseEncoding.base16().encode(md5Suffix))),
                type(Object.class));

        Parameter session = arg("session", ConnectorSession.class);
        Parameter value = arg("value", Block.class);

        MethodDefinition method = definition.declareMethod(
                a(PUBLIC, STATIC),
                "castRow",
                type(Block.class),
                session,
                value);

        Scope scope = method.getScope();
        BytecodeBlock body = method.getBody();

        Variable wasNull = scope.declareVariable(boolean.class, "wasNull");
        Variable blockBuilder = scope.createTempVariable(BlockBuilder.class);
        Variable singleRowBlockWriter = scope.createTempVariable(BlockBuilder.class);

        body.append(wasNull.set(constantBoolean(false)));

        CachedInstanceBinder cachedInstanceBinder = new CachedInstanceBinder(definition, binder);

        // create the row block builder
        body.append(blockBuilder.set(
                constantType(binder, toType).invoke(
                        "createBlockBuilder",
                        BlockBuilder.class,
                        newInstance(BlockBuilderStatus.class),
                        constantInt(1))));
        body.append(singleRowBlockWriter.set(blockBuilder.invoke("beginBlockEntry", BlockBuilder.class)));

        // loop through to append member blocks
        for (int i = 0; i < toTypes.size(); i++) {
            Signature signature = internalOperator(
                    CAST.name(),
                    toTypes.get(i).getTypeSignature(),
                    ImmutableList.of(fromTypes.get(i).getTypeSignature()));
            ScalarFunctionImplementation function = functionRegistry.getScalarFunctionImplementation(signature);
            Type currentFromType = fromTypes.get(i);
            if (currentFromType.equals(UNKNOWN)) {
                body.append(singleRowBlockWriter.invoke("appendNull", BlockBuilder.class).pop());
                continue;
            }
            BytecodeExpression fromElement = constantType(binder, currentFromType).getValue(value, constantInt(i));
            BytecodeExpression toElement = invokeFunction(scope, cachedInstanceBinder, signature.getName(), function, fromElement);
            IfStatement ifElementNull = new IfStatement("if the element in the row type is null...");

            ifElementNull.condition(value.invoke("isNull", boolean.class, constantInt(i)))
                    .ifTrue(singleRowBlockWriter.invoke("appendNull", BlockBuilder.class).pop())
                    .ifFalse(constantType(binder, toTypes.get(i)).writeValue(singleRowBlockWriter, toElement));

            body.append(ifElementNull);
        }

        // call blockBuilder.closeEntry() and return the single row block
        body.append(blockBuilder.invoke("closeEntry", BlockBuilder.class).pop());
        body.append(constantType(binder, toType)
                .invoke("getObject", Object.class, blockBuilder.cast(Block.class), constantInt(0))
                .cast(Block.class)
                .ret());

        // create constructor
        MethodDefinition constructorDefinition = definition.declareConstructor(a(PUBLIC));
        BytecodeBlock constructorBody = constructorDefinition.getBody();
        Variable thisVariable = constructorDefinition.getThis();
        constructorBody.comment("super();")
                .append(thisVariable)
                .invokeConstructor(Object.class);
        cachedInstanceBinder.generateInitializations(thisVariable, constructorBody);
        constructorBody.ret();

        return defineClass(definition, Object.class, binder.getBindings(), RowToRowCast.class.getClassLoader());
    }
}
