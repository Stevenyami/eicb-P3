/*******************************************************************************
 * Copyright (c) 2016-2019 Embedded Systems and Applications Group
 * Department of Computer Science, Technische Universitaet Darmstadt,
 * Hochschulstr. 10, 64289 Darmstadt, Germany.
 * <p>
 * All rights reserved.
 * <p>
 * This software is provided free for educational use only.
 * It may not be used for commercial purposes without the
 * prior written permission of the authors.
 ******************************************************************************/
package mavlc.codegen.tam;

import mavlc.errors.InapplicableOperationError;
import mavlc.errors.InternalCompilerError;
import mavlc.syntax.AstNode;
import mavlc.syntax.AstNodeBaseVisitor;
import mavlc.syntax.expression.*;
import mavlc.syntax.expression.Compare.Comparison;
import mavlc.syntax.function.FormalParameter;
import mavlc.syntax.function.Function;
import mavlc.syntax.module.Module;
import mavlc.syntax.record.RecordElementDeclaration;
import mavlc.syntax.statement.*;
import mavlc.type.*;
import mtam.Instruction;
import mtam.Primitive;
import mtam.Register;
import mtam.interpreter.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * EiCB group number: 40
 * Names and matriculation numbers of all group members:
 * Nyami Gochui Steve Jordi (2756527)
 * Julian von Hammel (2972165)
 * Nil Sila Ulucan (2378251)
 */

public class CodeGenerator extends AstNodeBaseVisitor<Instruction, Void> {
	
	protected final TamAssembler assembler;
	
	public CodeGenerator(TamAssembler asm) {
		assembler = asm;
	}
	
	@Override
	protected Instruction defaultOperation(AstNode node, Void __) {
		throw new UnsupportedOperationException("Code generation for this element is not implemented!");
	}
	
	/**
	 * A wrapper around the <code>AstNode.accept</code> method which also manages the context stack required for generating certain debug symbols.
	 *
	 * @param node The node to visit (node.accept(this) will be called).
	 * @return The return value of the accept() call.
	 */
	@Override
	public Instruction visit(AstNode node) {
		assembler.pushContext(node);
		Instruction result = node.accept(this);
		assembler.popContext();
		return result;
	}
	
	@Override
	public Instruction visitModule(Module module, Void __) {
		module.functions.forEach(this::visit);
		return null;
	}
	
	@Override
	public Instruction visitFunction(Function functionNode, Void __) {
		assembler.addNewFunction(functionNode);
		
		// set local base offset of all parameters
		int argOffset = 0;
		for(int i = functionNode.parameters.size() - 1; i >= 0; i--) {
			FormalParameter param = functionNode.parameters.get(i);
			argOffset -= param.getType().wordSize;
			param.setLocalBaseOffset(argOffset);
		}
		
		// generate code for statements
		functionNode.body.forEach(this::visit);
		
		// emit return here, since the arg offset is required
		assembler.emitReturn(functionNode.getReturnType().wordSize, -argOffset);
		
		return null;
	}

	@Override
	public Instruction visitValueDefinition(ValueDefinition valueDefinition, Void __) {
		visit(valueDefinition.value);
		assembler.addDeclaredEntity(valueDefinition);
		return null;
	}
	
	@Override
	public Instruction visitVariableDeclaration(VariableDeclaration variableDeclaration, Void __) {
		Type mavlType = variableDeclaration.getType();

		// Zero-initialize stack space for this variable
		assembler.
				emitPush(mavlType.wordSize)
				.addName(variableDeclaration.name)
				// The MTAM tracks types of stack entries for verification purposes, so set the right type for this
				// variable
				.addType(Value.Type.fromMavl(mavlType));
		assembler.addDeclaredEntity(variableDeclaration);
		return null;
	}
	
	@Override
	public Instruction visitVariableAssignment(VariableAssignment variableAssignment, Void __) {
		visit(variableAssignment.value);// auswertung der rechten Seite
		visit(variableAssignment.identifier); // auswertung der linken Seite der Zuweisung
		assembler.storeToStackAddress(variableAssignment.value.getType().wordSize); // Store address of variable on the stack
		return null;
	}
	
	@Override
	public Instruction visitLeftHandIdentifier(LeftHandIdentifier leftHandIdentifier, Void __) {
		int offset = leftHandIdentifier.getDeclaration().getLocalBaseOffset(); // Offset der Variable abrufen
		assembler.loadAddress(Register.LB, offset); // Load from local base
		return null;
	}
	
	@Override
	public Instruction visitMatrixLhsIdentifier(MatrixLhsIdentifier matrixLhsIdentifier, Void __) {
		final MatrixType type = (MatrixType) matrixLhsIdentifier.getDeclaration().getType();

		visitLeftHandIdentifier(matrixLhsIdentifier, __); // Auswertung der Linke Seite
		visit(matrixLhsIdentifier.rowIndexExpression); // Auswertung der Row-Index
		assembler.emitBoundsCheck(0, type.rows);// Bounds check

		// Get matrix entry offset: number of row * row index + col index (  Here: matrix[row][0] )
		assembler.loadIntegerValue(type.cols);
		assembler.emitIntegerMultiplication();
		assembler.emitIntegerAddition();

		visit(matrixLhsIdentifier.colIndexExpression); // Evaluate column index
		assembler.emitBoundsCheck(0, type.cols); // Boundary check

		// Here: matrix[row][col]
		assembler.emitIntegerAddition();
		return null;
	}
	
	@Override
	public Instruction visitVectorLhsIdentifier(VectorLhsIdentifier vectorLhsIdentifier, Void __) {
		final VectorType Vectype = (VectorType) vectorLhsIdentifier.getDeclaration().getType();

		visitLeftHandIdentifier(vectorLhsIdentifier, __); // Evaluate the left-hand side
		visit(vectorLhsIdentifier.indexExpression); // Evaluate row index

		assembler.emitBoundsCheck(0, Vectype.dimension);// Boundary check
		assembler.emitIntegerAddition();
		return null;
	}
	
	@Override
	public Instruction visitRecordLhsIdentifier(RecordLhsIdentifier recordLhsIdentifier, Void __) {
		final Declaration declaration = recordLhsIdentifier.getDeclaration();
		final RecordType type = (RecordType) declaration.getType();
		final int baseOffset = declaration.getLocalBaseOffset();

		// Retrieve offset of field
		int offset = 0;
		for (final RecordElementDeclaration element : type.typeDeclaration.elements) {
			if (element.name.equals(recordLhsIdentifier.elementName)) {
				break;
			}
			offset += element.getType().wordSize;
		}

		assembler.loadAddress(Register.LB, baseOffset + offset); // Load from local base
		return null;
	}
	
	@Override
	public Instruction visitForLoop(ForLoop forLoop, Void __) {
		int initVarOffset = forLoop.getInitVarDeclaration().getLocalBaseOffset();
		int incrVarOffset = forLoop.getIncrVarDeclaration().getLocalBaseOffset();
		
		// evaluate init expression and store to the init variable
		visit(forLoop.initExpression);
		// ..., initValue
		assembler.storeLocalValue(forLoop.initExpression.getType().wordSize, initVarOffset);
		// ...
		
		// jump to the loop condition (this jump needs to backpatched later on)
		Instruction jumpToCondition = assembler.emitJump(-1);
		int loopBodyStart = assembler.getNextInstructionAddress();
		
		// emit loop body
		int nextOffset = assembler.getNextOffset();
		visit(forLoop.body);
		assembler.resetNextOffset(nextOffset);
		
		// evaluate increment expression and store it to the increment variable
		visit(forLoop.incrExpression);
		// ..., incrValue
		assembler.storeLocalValue(forLoop.incrExpression.getType().wordSize, incrVarOffset);
		// ...
		
		// backpatch the jump to the test
		int loopCondition = assembler.getNextInstructionAddress();
		assembler.backPatchJump(jumpToCondition, loopCondition);
		
		// evaluate the loop condition
		visit(forLoop.loopCondition);
		// ..., bool
		assembler.emitConditionalJump(true, loopBodyStart);
		// ...
		
		return null;
	}
	
	@Override
	public Instruction visitForEachLoop(ForEachLoop forEachLoop, Void __) {

		int localSize = assembler.getNextOffset(); // save current stack size reserved for locals

		Expression StructExpr = forEachLoop.structExpression; // hole die Expression , die die Struktur representiert
		IteratorDeclaration iterator = forEachLoop.iteratorDeclaration; //The iterator used for iterating the struct.
		int elementCount = StructExpr.getType().wordSize;

		// get base address of the StructExpr and iterator lb offset
		int structBase;
		boolean popStruct = false;
		if (StructExpr instanceof IdentifierReference) {
			structBase = ((IdentifierReference) StructExpr).getDeclaration().getLocalBaseOffset(); // use base address of the identifier

		} else { // evaluate the StructExpr, base address is the current stack top

			popStruct = true;
			structBase = assembler.getNextOffset();
			visit(StructExpr);
			assembler.setNextOffset(assembler.getNextOffset() + elementCount); // reserve stack space for StructExpr
		}

		// load initial value for i
		assembler.loadIntegerValue(0);
		assembler.setNextOffset(assembler.getNextOffset() + 1);

		// reserve space for iterator
		iterator.setLocalBaseOffset(assembler.getNextOffset());
		assembler.setNextOffset(assembler.getNextOffset() + 1);

		// loop condition (i < StructExpr.wordSize)
		int loopCondition = assembler.getNextInstructionAddress();
		// ..., i
		assembler.loadValue(Register.ST, 1, -1);
		// ..., i, i
		assembler.loadIntegerValue(elementCount);
		// ..., i, i, count
		assembler.emitIntegerComparison(Comparison.LESS);
		// ..., i, bool
		Instruction jumpToLoopEnd = assembler.emitConditionalJump(false, -1);
		// ..., i

		// loop body
		{
			// populate iterator (current = StructExpr[i])
			// ..., i
			assembler.loadValue(Register.ST, 1, -1);
			// ..., i, i
			assembler.loadAddress(Register.LB, structBase);
			// ..., i, i, &StructExpr
			assembler.emitIntegerAddition();
			// ..., i, &StructExpr[i]
			assembler.loadFromStackAddress(1);
			// ..., i, current

			// execute body
			int nextOffset = assembler.getNextOffset();
			visit(forEachLoop.body);
			assembler.resetNextOffset(nextOffset);

			// save value and proceed to next iteration
			if (iterator.isVariable()) {
				// ..., i, current
				assembler.loadValue(Register.ST, 1, -2);
				// ..., i, current, i
				assembler.loadAddress(Register.LB, structBase);
				// ..., i, current, i, &StructExpr
				assembler.emitIntegerAddition();
				// ..., i, current, &StructExpr[i]
				assembler.storeToStackAddress(1);
				// ..., i
			} else {
				// ..., i, current
				assembler.emitPop(0, 1);
				// ..., i
			}
			// ..., i
			assembler.emitIncrement();
			// ..., i+1
		}
		assembler.emitJump(loopCondition);

		int loopEnd = assembler.getNextInstructionAddress();
		assembler.backPatchJump(jumpToLoopEnd, loopEnd);

		// pop auxiliary values
		if (popStruct)
			assembler.emitPop(0, elementCount + 1);
		else
			assembler.emitPop(0, 1);

		assembler.setNextOffset(localSize); // reset local stack size
		return null;
	}
	
	@Override
	public Instruction visitIfStatement(IfStatement ifStatement, Void __) {
		int nextOffset = assembler.getNextOffset();
		boolean emitElse = ifStatement.hasElseStatement();
		
		// evaluate condition
		visit(ifStatement.condition);
		// if the condition is false, skip the 'then' branch
		Instruction jumpOverThenBranch = assembler.emitConditionalJump(false, -1);
		
		// emit then branch
		visit(ifStatement.thenStatement);
		assembler.resetNextOffset(nextOffset);
		
		// if the statement has an else branch, emit a jump to skip it
		Instruction jumpOverElseBranch = null;
		if(emitElse) jumpOverElseBranch = assembler.emitJump(-1);
		
		// backpatch jump over 'then' branch
		int endOfThenBranch = assembler.getNextInstructionAddress();
		assembler.backPatchJump(jumpOverThenBranch, endOfThenBranch);
		
		if(emitElse) {
			assert ifStatement.elseStatement != null;
			
			// emit else branch
			visit(ifStatement.elseStatement);
			assembler.resetNextOffset(nextOffset);
			
			// backpatch jump over 'else' branch
			int endOfElseBranch = assembler.getNextInstructionAddress();
			assembler.backPatchJump(jumpOverElseBranch, endOfElseBranch);
		}
		return null;
	}
	
	@Override
	public Instruction visitCallStatement(CallStatement callStatement, Void __) {
		visit(callStatement.callExpression);
		
		// discard return value if it exists
		int resultSize = callStatement.callExpression.getCalleeDefinition().getReturnType().wordSize;
		if(resultSize != 0) assembler.emitPop(0, resultSize).addComment("discard return value", false);
		
		return null;
	}
	
	@Override
	public Instruction visitReturnStatement(ReturnStatement returnStatement, Void __) {
		// leave the return value on the stack, the RETURN instruction is emitted in visitFunction
		visit(returnStatement.returnValue);
		return null;
	}
	
	@Override
	public Instruction visitCompoundStatement(CompoundStatement compoundStatement, Void __) {
		final int localSize = assembler.getNextOffset(); // Stack Size bevor dem CompoundStatement
		compoundStatement.statements.forEach(this :: visit); // Evaluieren alle Statement des CompoundStatement
		assembler.resetNextOffset(localSize); // Wiederherstellen des Stack Size bevor der Reservierung den Plätzen von locale Variablen
		return null;
	}
	
	@Override
	public Instruction visitSwitchStatement(SwitchStatement switchCaseStatement, Void __) {
		// location of the condition on the stack
		int localSize = assembler.getNextOffset();
		
		// jump at the end of each case
		List<Instruction> jumps = new ArrayList<>();
		
		// evaluate switch condition. We intend to keep it on the stack while the switch statement runs, so increase
		// the offset for upcoming variables too.
		visit(switchCaseStatement.condition);
		assembler.setNextOffset(localSize + switchCaseStatement.condition.getType().wordSize);
		
		for(Case namedCase : switchCaseStatement.cases) {
			// place switch condition on the stack
			assembler.loadIntegerValue(localSize);
			assembler.loadFromStackAddress(1);
			// save jump to backpatch later
			jumps.add(visit(namedCase));
		}
		
		if(!switchCaseStatement.defaults.isEmpty()) {
			visit(switchCaseStatement.defaults.get(0));
		}
		
		// backpatch all jumps from named cases
		int switchEnd = assembler.getNextInstructionAddress();
		for(Instruction jump : jumps) {
			assembler.backPatchJump(jump, switchEnd);
		}
		
		// Reset the offset to pop the condition off the stack
		assembler.resetNextOffset(localSize);
		return null;
	}
	
	@Override
	public Instruction visitCase(Case namedCase, Void __) {
		// ..., switchValue
		assembler.loadIntegerValue(namedCase.getCondition());
		// ..., switchValue, condition
		assembler.emitIntegerComparison(Comparison.EQUAL);
		// ..., bool
		
		// skip case body if the conditions mismatch
		Instruction jumpNextCase = assembler.emitConditionalJump(false, -1);
		
		int nextOffset = assembler.getNextOffset();
		visit(namedCase.body);
		// restore size reserved for locals
		assembler.resetNextOffset(nextOffset);
		
		Instruction jumpSwitchEnd = assembler.emitJump(0);
		int startNextCase = assembler.getNextInstructionAddress();
		assembler.backPatchJump(jumpNextCase, startNextCase);
		
		return jumpSwitchEnd;
	}
	
	@Override
	public Instruction visitDefault(Default defaultCase, Void __) {
		int nextOffset = assembler.getNextOffset();
		visit(defaultCase.body);
		// restore size reserved for locals
		assembler.resetNextOffset(nextOffset);
		return null;
	}
	
	@Override
	public Instruction visitMatrixMultiplication(MatrixMultiplication matrixMultiplication, Void __) {
		visit(matrixMultiplication.leftOperand);
		visit(matrixMultiplication.rightOperand);
		// hole die StructTypen der Operanden ab
		StructType leftOperandType = (StructType) matrixMultiplication.leftOperand.getType();
		StructType rightOperandType = (StructType) matrixMultiplication.rightOperand.getType();

		if (!(leftOperandType instanceof MatrixType)){ // error, wenn die Typen nicht instance of MatrixTypen sind
			throw new InapplicableOperationError(matrixMultiplication, leftOperandType, MatrixType.class);
		}
		if (!(rightOperandType instanceof MatrixType)){
			throw new InapplicableOperationError(matrixMultiplication, rightOperandType, MatrixType.class);
		}

		MatrixType leftMatrix = (MatrixType) leftOperandType;// die OperandenTypen in MatrixTypen casten
		MatrixType rightMatrix = (MatrixType) rightOperandType;
		// load werten auf den Stack
		assembler.loadIntegerValue(leftMatrix.rows);
		assembler.loadIntegerValue(leftMatrix.cols);
		assembler.loadIntegerValue(rightMatrix.cols);

		if (leftOperandType.elementType.equals(IntType.instance))
			assembler.emitIntegerMatrixMultiplication();
		else
			assembler.emitFloatMatrixMultiplication();
		return null;
	}
	
	@Override
	public Instruction visitDotProduct(DotProduct dotProduct, Void __) {
		VectorType vectorType = (VectorType) dotProduct.leftOperand.getType();
		
		visit(dotProduct.leftOperand);
		visit(dotProduct.rightOperand);
		
		assembler.loadIntegerValue(1);
		assembler.loadIntegerValue(vectorType.dimension);
		assembler.loadIntegerValue(1);
		
		// a dot product is basically a matrix multiplication of one vector with the other's transposed form
		if(vectorType.elementType.equals(IntType.instance))
			assembler.emitIntegerMatrixMultiplication();
		else
			assembler.emitFloatMatrixMultiplication();
		
		return null;
	}
	
	private void visitArithmeticOperator(BinaryExpression node, boolean allowLeftStruct, boolean allowRightStruct, boolean allowBothStruct, Primitive intPrimitive, Primitive floatPrimitive) {
		Type resultType = node.getType();
		Type leftType = node.leftOperand.getType();
		Type rightType = node.rightOperand.getType();
		int lSize = leftType.wordSize;
		int rSize = rightType.wordSize;
		
		// evaluate operands
		visit(node.leftOperand);
		visit(node.rightOperand);
		
		if(resultType.equals(IntType.instance)) {
			assembler.callPrimitive(intPrimitive);
			return;
		}
		
		if(resultType.equals(FloatType.instance)) {
			assembler.callPrimitive(floatPrimitive);
			return;
		}
		
		if(leftType instanceof StructType && rightType instanceof StructType) {
			if(!allowBothStruct)
				throw new InternalCompilerError(node.getClass().getSimpleName() + " does not support structures for both operands");
			
			boolean useIntPrimitive = ((StructType) leftType).elementType.equals(IntType.instance);
			
			// ..., left, right
			assembler.loadIntegerValue(0);
			// ..., left, right, i (0)
			int loopBegin = assembler.getNextInstructionAddress();
			
			// load operands
			assembler.loadValue(Register.ST, 1, -1);
			// ..., left, right, i, i
			assembler.loadAddress(Register.ST, -2 - lSize - rSize);
			// ..., left, right, i, i, &left
			assembler.emitIntegerAddition();
			// ..., left, right, i, &left[i]
			assembler.loadFromStackAddress(1);
			// ..., left, right, i, left[i]
			assembler.loadValue(Register.ST, 1, -2);
			// ..., left, right, i, left[i], i
			assembler.loadAddress(Register.ST, -3 - rSize);
			// ..., left, right, i, left[i], i, &right
			assembler.emitIntegerAddition();
			// ..., left, right, i, left[i], &right[i]
			assembler.loadFromStackAddress(1);
			// ..., left, right, i, left[i], right[i]
			
			// combine and store
			assembler.callPrimitive(useIntPrimitive ? intPrimitive : floatPrimitive);
			// ..., left, right, i, elem
			assembler.loadValue(Register.ST, 1, -2);
			// ..., left, right, i, elem, i
			assembler.loadAddress(Register.ST, -3 - lSize - rSize);
			// ..., left, right, i, elem, i, &left
			assembler.emitIntegerAddition();
			// ..., left, right, i, elem, &left[i]
			assembler.storeToStackAddress(1);
			// ..., left, right, i
			
			// increment and check
			assembler.emitIncrement();
			// ..., left, right, i+1
			assembler.loadValue(Register.ST, 1, -1);
			// ..., left, right, i+1, i+1
			assembler.loadIntegerValue(lSize);
			// ..., left, right, i+1, i+1, size
			assembler.emitIntegerComparison(Comparison.LESS);
			// ..., left, right, i+1, bool
			assembler.emitConditionalJump(true, loopBegin);
			// ..., left, right, i+1
			assembler.emitPop(0, 1 + rSize);
			// ..., result
			
			return;
		}
		
		if(leftType instanceof StructType) {
			if(!allowLeftStruct)
				throw new InternalCompilerError(node.getClass().getSimpleName() + " does not support structures for its left operand");
			
			boolean useIntPrimitive = rightType.equals(IntType.instance);
			
			// ..., struct, num
			assembler.loadIntegerValue(0);
			// ..., struct, num, i (0)
			int loopStart = assembler.getNextInstructionAddress();
			
			// load operands
			assembler.loadValue(Register.ST, 1, -1);
			// ..., struct, num, i, i
			assembler.loadAddress(Register.ST, -3 - lSize);
			// ..., struct, num, i, i, &struct
			assembler.emitIntegerAddition();
			// ..., struct, num, i, &struct[i]
			assembler.loadFromStackAddress(1);
			// ..., struct, num, i, struct[i]
			assembler.loadValue(Register.ST, 1, -3);
			// ..., struct, num, i, struct[i], num
			
			// combine and store
			assembler.callPrimitive(useIntPrimitive ? intPrimitive : floatPrimitive);
			// ..., struct, num, i, elem
			assembler.loadValue(Register.ST, 1, -2);
			// ..., struct, num, i, elem, i
			assembler.loadAddress(Register.ST, -4 - lSize);
			// ..., struct, num, i, elem, i, &struct
			assembler.emitIntegerAddition();
			// ..., struct, num, i, elem, &struct[i]
			assembler.storeToStackAddress(1);
			// ..., struct, num, i
			
			// increment and check
			assembler.emitIncrement();
			// ..., struct, num, i+1
			assembler.loadValue(Register.ST, 1, -1);
			// ..., struct, num, i+1, i+1
			assembler.loadIntegerValue(lSize);
			// ..., struct, num, i+1, i+1, size
			assembler.emitIntegerComparison(Comparison.LESS);
			// ..., struct, num, i+1, bool
			assembler.emitConditionalJump(true, loopStart);
			// ..., struct, num, i+1
			assembler.emitPop(0, 2);
			// ..., struct
			
			return;
		}
		
		if(rightType instanceof StructType) {
			if(!allowRightStruct)
				throw new InternalCompilerError(node.getClass().getSimpleName() + " does not support structures for its right operand");
			
			boolean useIntPrimitive = leftType.equals(IntType.instance);
			
			// ..., num, struct
			assembler.loadIntegerValue(0);
			// ..., num, struct, i (0)
			int loopStart = assembler.getNextInstructionAddress();
			
			// load operands
			assembler.loadValue(Register.ST, 1, -1);
			// ..., num, struct, i, i
			assembler.loadAddress(Register.ST, -2 - rSize);
			// ..., num, struct, i, i, &struct
			assembler.emitIntegerAddition();
			// ..., num, struct, i, &struct[i]
			assembler.loadFromStackAddress(1);
			// ..., num, struct, i, struct[i]
			assembler.loadValue(Register.ST, 1, -3 - rSize);
			// ..., num, struct, i, struct[i], num
			
			// combine and store
			assembler.callPrimitive(useIntPrimitive ? intPrimitive : floatPrimitive);
			// ..., num, struct, i, elem
			assembler.loadValue(Register.ST, 1, -2);
			// ..., num, struct, i, elem, i
			assembler.loadAddress(Register.ST, -3 - rSize);
			// ..., num, struct, i, elem, i, &struct
			assembler.emitIntegerAddition();
			// ..., num, struct, i, elem, &struct[i]
			assembler.storeToStackAddress(1);
			// ..., num, struct, i
			
			// increment and check
			assembler.emitIncrement();
			// ..., num, struct, i+1
			assembler.loadValue(Register.ST, 1, -1);
			// ..., num, struct, i+1, i+1
			assembler.loadIntegerValue(rSize);
			// ..., num, struct, i+1, i+1, size
			assembler.emitIntegerComparison(Comparison.LESS);
			// ..., num, struct, i+1, bool
			assembler.emitConditionalJump(true, loopStart);
			// ..., num, struct, i+1
			assembler.emitPop(0, 1);
			// ..., num, struct
			assembler.emitPop(rSize, 1);
			// ..., struct
			
			return;
		}
		
		throw new InternalCompilerError("How did we even get here?");
	}
	
	@Override
	public Instruction visitAddition(Addition addition, Void __) {
		visitArithmeticOperator(addition, false, false, true, Primitive.addI, Primitive.addF);
		return null;
	}
	
	@Override
	public Instruction visitSubtraction(Subtraction subtraction, Void __) {
		visitArithmeticOperator(subtraction, false, false, true, Primitive.subI, Primitive.subF);
		return null;
	}
	
	@Override
	public Instruction visitMultiplication(Multiplication multiplication, Void __) {
		visitArithmeticOperator(multiplication, true, true, true, Primitive.mulI, Primitive.mulF);
		return null;
	}
	
	@Override
	public Instruction visitDivision(Division division, Void __) {
		visitArithmeticOperator(division, false, false, false, Primitive.divI, Primitive.divF);
		return null;
	}
	
	@Override
	public Instruction visitExponentiation(Exponentiation exponentiation, Void __) {
		visitArithmeticOperator(exponentiation, false, false, false, Primitive.powInt, Primitive.powFloat);
		return null;
	}
	
	@Override
	public Instruction visitCompare(Compare compare, Void __) {
		visit(compare.leftOperand); // hole Expression LeftOperand ab
		visit(compare.rightOperand); // hole Expression RightOperand ab
		if (compare.leftOperand.getType().equals(IntType.instance)){
			assembler.emitIntegerComparison(compare.comparator);
		} else {
			assembler.emitFloatComparison(compare.comparator);
		}
		return null;
	}
	
	@Override
	public Instruction visitAnd(And and, Void __) {
		visit(and.leftOperand);
		visit(and.rightOperand);
		assembler.emitLogicalAnd();
		return null;
	}
	
	@Override
	public Instruction visitOr(Or or, Void __) {
		visit(or.leftOperand);
		visit(or.rightOperand);
		assembler.emitLogicalOr();
		return null;
	}
	
	@Override
	public Instruction visitMatrixTranspose(MatrixTranspose matrixTranspose, Void __) {
		MatrixType type = (MatrixType) matrixTranspose.operand.getType();
		visit(matrixTranspose.operand);
		
		// transposition of a single row or column is a no-op
		if(type.cols <= 1 || type.rows <= 1) return null;
		assembler.loadIntegerValue(type.rows);
		assembler.loadIntegerValue(type.cols);
		assembler.emitMatrixTranspose();
		return null;
	}
	
	@Override
	public Instruction visitMatrixRows(MatrixRows rows, Void __) {
		MatrixType type = (MatrixType) rows.operand.getType();
		assembler.loadIntegerValue(type.rows).addComment("matrix rows", false);
		return null;
	}
	
	@Override
	public Instruction visitMatrixCols(MatrixCols cols, Void __) {
		MatrixType type = (MatrixType) cols.operand.getType();
		assembler.loadIntegerValue(type.cols).addComment("matrix cols", false);
		return null;
	}
	
	@Override
	public Instruction visitVectorDimension(VectorDimension vectorDimension, Void __) {
		VectorType type = (VectorType) vectorDimension.operand.getType();
		assembler.loadIntegerValue(type.dimension).addComment("vector dim", false);
		return null;
	}
	
	@Override
	public Instruction visitUnaryMinus(UnaryMinus unaryMinus, Void __) {
		visit(unaryMinus.operand);
		if(unaryMinus.getType().equals(IntType.instance))
			assembler.emitIntegerNegation();
		else
			assembler.emitFloatNegation();
		return null;
	}
	
	@Override
	public Instruction visitNot(Not not, Void __) {
		visit(not.operand);
		assembler.emitLogicalNot();
		return null;
	}
	
	@Override
	public Instruction visitCallExpression(CallExpression callExpression, Void __) {
		callExpression.actualParameters.forEach(this :: visit);
		assembler.emitFunctionCall(callExpression.getCalleeDefinition());
		return null;
	}
	
	@Override
	public Instruction visitElementSelect(ElementSelect elementSelect, Void __) {
		StructType strType = (StructType) elementSelect.structExpression.getType();
		Type resultType = elementSelect.getType();
		int structSize = strType.wordSize;
		int resultSize = resultType.wordSize;
		int upperBound = structSize / resultSize;

		// ...
		visit(elementSelect.structExpression);
		// ..., struct
		assembler.loadAddress(Register.ST, -structSize);
		// ..., struct, &struct
		visit(elementSelect.indexExpression);
		assembler.emitBoundsCheck(0, upperBound);
		// ..., struct, &struct, index
		if (resultSize != 1) {
			assembler.loadIntegerValue(resultSize);
			assembler.emitIntegerMultiplication();
		}
		assembler.emitIntegerAddition();
		// ..., struct, &struct[index]
		assembler.loadFromStackAddress(resultSize);
		// ..., struct, result
		assembler.emitPop(resultSize, structSize);
		// ..., result
		return null;
	}
	
	@Override
	public Instruction visitRecordElementSelect(RecordElementSelect recordElementSelect, Void __) {
		final Type eltType = recordElementSelect.getType(); // holen wir den Typ ab
		final RecordType recordType = (RecordType) recordElementSelect.recordExpression.getType();
		final int offset = recordType.typeDeclaration.getElementOffset(recordElementSelect.elementName);

		// Evaluate the record expression
		visit(recordElementSelect.recordExpression);
		assembler.loadAddress(Register.ST, -recordType.wordSize + offset);
		assembler.loadFromStackAddress(eltType.wordSize);
		assembler.emitPop(eltType.wordSize, recordType.wordSize);
		return null;
	}
	
	@Override
	public Instruction visitSubMatrix(SubMatrix subMatrix, Void __) {
		MatrixType matrix = (MatrixType) subMatrix.structExpression.getType();
		MatrixType result = (MatrixType) subMatrix.getType();
		int matSize = matrix.wordSize;
		int resSize = result.wordSize;
		
		int rowStartOffset = subMatrix.getRowStartOffset();
		int colStartOffset = subMatrix.getColStartOffset();
		
		// ...
		visit(subMatrix.structExpression);
		// ..., mat
		assembler.emitPush(resSize);
		// ..., mat, res
		assembler.loadAddress(Register.ST, -matSize - resSize);
		// ..., mat, res, &mat
		visit(subMatrix.rowBaseIndexExpression);
		if(rowStartOffset != 0) {
			assembler.loadIntegerValue(rowStartOffset);
			assembler.emitIntegerAddition();
		}
		assembler.emitBoundsCheck(0, matrix.rows - result.rows + 1);
		// ..., mat, res, &mat, minRow
		assembler.loadIntegerValue(matrix.cols);
		assembler.emitIntegerMultiplication();
		assembler.emitIntegerAddition();
		// ..., mat, res, &mat[minRow][0]
		visit(subMatrix.colBaseIndexExpression);
		if(colStartOffset != 0) {
			assembler.loadIntegerValue(colStartOffset);
			assembler.emitIntegerAddition();
		}
		assembler.emitBoundsCheck(0, matrix.cols - result.cols + 1);
		// ..., mat, res, &mat[minRow][0], minCol
		assembler.emitIntegerAddition();
		// ..., mat, res, &mat[minRow][minCol]
		assembler.loadAddress(Register.ST, -resSize - 1);
		// ..., mat, res, &mat[minRow][minCol], &res
		assembler.loadIntegerValue(0);
		// ..., mat, res, &mat[minRow][minCol], &res, i (0)
		
		// loop header
		int loopStart = assembler.getNextInstructionAddress();
		// ..., mat, res, srcPtr, dstPtr, i
		assembler.loadValue(Register.ST, 1, -1);
		// ..., mat, res, srcPtr, dstPtr, i, i
		Instruction jumpEnd = assembler.emitConditionalJump(result.rows, -1); // break if i == result.rows
		// ..., mat, res, srcPtr, dstPtr, i
		
		// copy row
		assembler.loadValue(Register.ST, 1, -3);
		// ..., mat, res, srcPtr, dstPtr, i, srcPtr
		assembler.loadFromStackAddress(result.cols);
		// ..., mat, res, srcPtr, dstPtr, i, row
		assembler.loadValue(Register.ST, 1, -2 - result.cols);
		// ..., mat, res, srcPtr, dstPtr, i, row, dstPtr
		assembler.storeToStackAddress(result.cols);
		// ..., mat, res, srcPtr, dstPtr, i
		
		// increment
		assembler.loadValue(Register.ST, 1, -3);
		// ..., mat, res, srcPtr, dstPtr, i, srcPtr
		assembler.loadIntegerValue(matrix.cols);
		// ..., mat, res, srcPtr, dstPtr, i, srcPtr, matCols
		assembler.emitIntegerAddition();
		// ..., mat, res, srcPtr, dstPtr, i, srcPtr'
		assembler.storeValue(Register.ST, 1, -4);
		// ..., mat, res, srcPtr', dstPtr, i
		assembler.loadValue(Register.ST, 1, -2);
		// ..., mat, res, srcPtr', dstPtr, i, dstPtr
		assembler.loadIntegerValue(result.cols);
		// ..., mat, res, srcPtr', dstPtr, i, dstPtr, resCols
		assembler.emitIntegerAddition();
		// ..., mat, res, srcPtr', dstPtr, i, dstPtr'
		assembler.storeValue(Register.ST, 1, -3);
		// ..., mat, res, srcPtr', dstPtr', i
		assembler.emitIncrement();
		// ..., mat, res, srcPtr', dstPtr', i'
		
		assembler.emitJump(loopStart);
		int loopEnd = assembler.getNextInstructionAddress();
		assembler.backPatchJump(jumpEnd, loopEnd);
		
		// ..., mat, res, srcPtr, dstPtr, i
		assembler.emitPop(0, 3);
		// ..., mat, res
		assembler.emitPop(resSize, matSize);
		// ..., res
		
		return null;
	}
	
	@Override
	public Instruction visitSubVector(SubVector subVector, Void __) {
		int startOffset = subVector.getStartOffset();
		
		int resSize = subVector.getType().wordSize;
		int vecSize = subVector.structExpression.getType().wordSize;
		
		// ...
		visit(subVector.structExpression);
		// ..., vec
		assembler.loadAddress(Register.ST, -vecSize);
		// ..., vec, &vec
		visit(subVector.baseIndexExpression);
		// ..., vec, &vec, base
		assembler.loadIntegerValue(startOffset);
		// ..., vec, &vec, base, start
		assembler.emitIntegerAddition();
		// ..., vec, &vec, base+start
		assembler.emitBoundsCheck(0, vecSize - resSize + 1);
		// ..., vec, &vec, base+start
		assembler.emitIntegerAddition();
		// ..., vec, &vec[base+start]
		assembler.loadFromStackAddress(resSize);
		// ..., vec, result
		assembler.emitPop(resSize, vecSize);
		// ..., result
		
		return null;
	}
	
	@Override
	public Instruction visitStructureInit(StructureInit structureInit, Void __) {
		structureInit.elements.forEach(this::visit);
		return null;
	}
	
	@Override
	public Instruction visitStringValue(StringValue stringValue, Void __) {
		assembler.loadStringValue(stringValue.value);
		return null;
	}
	
	@Override
	public Instruction visitBoolValue(BoolValue boolValue, Void __) {
		assembler.loadBooleanValue(boolValue.value);
		return null;
	}
	
	@Override
	public Instruction visitIntValue(IntValue intValue, Void __) {
		assembler.loadIntegerValue(intValue.value);
		return null;
	}
	
	@Override
	public Instruction visitFloatValue(FloatValue floatValue, Void __) {
		assembler.loadFloatValue(floatValue.value);
		return null;
	}
	
	@Override
	public Instruction visitIdentifierReference(IdentifierReference identifierReference, Void __) {
		Declaration decl = identifierReference.getDeclaration();
		int wordSize = decl.getType().wordSize;
		int offset = decl.getLocalBaseOffset();
		assembler.loadLocalValue(wordSize, offset).addName(identifierReference.name)
		/*/.addComment("load identifier '" + identifierReference.name + "'")/**/;
		return null;
	}
	
	@Override
	public Instruction visitSelectExpression(SelectExpression exp, Void __) {
		// evaluate condition
		visit(exp.condition);
		// jump over 'then' branch
		Instruction jumpOverThen = assembler.emitConditionalJump(false, -1);
		visit(exp.trueCase);
		Instruction jumpOverElse = assembler.emitJump(-1);
		
		// backpatch jump to 'else' branch
		int afterThen = assembler.getNextInstructionAddress();
		assembler.backPatchJump(jumpOverThen, afterThen);
		visit(exp.falseCase);
		
		// backpatch jump over 'else' branch
		int afterElse = assembler.getNextInstructionAddress();
		assembler.backPatchJump(jumpOverElse, afterElse);
		
		return null;
	}
}
