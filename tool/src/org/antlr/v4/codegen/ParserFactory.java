/*
 [The "BSD license"]
 Copyright (c) 2011 Terence Parr
 All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions
 are met:

 1. Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.
 2. Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in the
    documentation and/or other materials provided with the distribution.
 3. The name of the author may not be used to endorse or promote products
    derived from this software without specific prior written permission.

 THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.antlr.v4.codegen;

import org.antlr.v4.analysis.AnalysisPipeline;
import org.antlr.v4.codegen.model.*;
import org.antlr.v4.codegen.model.ast.*;
import org.antlr.v4.codegen.model.decl.*;
import org.antlr.v4.parse.ANTLRParser;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.semantics.UseDefAnalyzer;
import org.antlr.v4.tool.*;

import java.util.List;

/** */
public class ParserFactory extends DefaultOutputModelFactory {
	public ParserFactory(CodeGenerator gen) { super(gen); }

	public ParserFile parserFile(String fileName) {
		return new ParserFile(this, fileName);
	}

	public Parser parser(ParserFile file) {
		return new Parser(this, file);
	}

	public RuleFunction rule(Rule r) {
		return new RuleFunction(this, r);
	}

	public CodeBlockForAlt epsilon() { return new CodeBlockForAlt(this); }

	public CodeBlockForAlt alternative(Alternative alt) { return new CodeBlockForAlt(this); }

	@Override
	public CodeBlockForAlt finishAlternative(CodeBlockForAlt blk, List<SrcOp> ops) {
		blk.ops = ops;
		return blk;
	}

	public List<SrcOp> action(GrammarAST ast) { return list(new Action(this, ast)); }

	public List<SrcOp> forcedAction(GrammarAST ast) { return list(new ForcedAction(this, ast)); }

	public List<SrcOp> sempred(GrammarAST ast) { return list(new SemPred(this, ast)); }

	public List<SrcOp> ruleRef(GrammarAST ID, GrammarAST label, GrammarAST args) {
		InvokeRule invokeOp = new InvokeRule(this, ID, label);
		// If no manual label and action refs as token/rule not label or
		// we're adding to trees, we need to define implicit label
		if ( controller.needsImplicitLabel(ID, invokeOp) ) defineImplicitLabel(ID, invokeOp);
		AddToLabelList listLabelOp = getListLabelIfPresent(invokeOp, label);
		return list(invokeOp, listLabelOp);
	}

	public List<SrcOp> tokenRef(GrammarAST ID, GrammarAST label, GrammarAST args) {
		LabeledOp matchOp = new MatchToken(this, (TerminalAST) ID, label);
		if ( controller.needsImplicitLabel(ID, matchOp) ) defineImplicitLabel(ID, matchOp);
		AddToLabelList listLabelOp = getListLabelIfPresent(matchOp, label);
		return list(matchOp, listLabelOp);
	}

	public Choice getChoiceBlock(BlockAST blkAST, List<CodeBlockForAlt> alts) {
		int decision = ((DecisionState)blkAST.atnState).decision;
		if ( AnalysisPipeline.disjoint(g.decisionLOOK.get(decision)) ) {
			return getLL1ChoiceBlock(blkAST, alts);
		}
		else {
			return getLLStarChoiceBlock(blkAST, alts);
		}
	}

	public Choice getEBNFBlock(GrammarAST ebnfRoot, List<CodeBlockForAlt> alts) {
		int decision;
		if ( ebnfRoot.getType()==ANTLRParser.POSITIVE_CLOSURE ) {
			decision = ((PlusBlockStartState)ebnfRoot.atnState).loopBackState.decision;
		}
		else if ( ebnfRoot.getType()==ANTLRParser.CLOSURE ) {
			decision = ((BlockStartState)ebnfRoot.atnState).decision;
		}
		else {
			decision = ((DecisionState)ebnfRoot.atnState).decision;
		}
		if ( AnalysisPipeline.disjoint(g.decisionLOOK.get(decision)) ) {
			return getLL1EBNFBlock(ebnfRoot, alts);
		}
		else {
			return getLLStarEBNFBlock(ebnfRoot, alts);
		}
	}

	public Choice getLL1ChoiceBlock(BlockAST blkAST, List<CodeBlockForAlt> alts) {
		return new LL1AltBlock(this, blkAST, alts);
	}

	public Choice getLLStarChoiceBlock(BlockAST blkAST, List<CodeBlockForAlt> alts) {
		return new AltBlock(this, blkAST, alts);
	}

	public Choice getLL1EBNFBlock(GrammarAST ebnfRoot, List<CodeBlockForAlt> alts) {
		int ebnf = 0;
		if ( ebnfRoot!=null ) ebnf = ebnfRoot.getType();
		Choice c = null;
		switch ( ebnf ) {
			case ANTLRParser.OPTIONAL :
				if ( alts.size()==1 ) c = new LL1OptionalBlockSingleAlt(this, ebnfRoot, alts);
				else c = new LL1OptionalBlock(this, ebnfRoot, alts);
				break;
			case ANTLRParser.CLOSURE :
				if ( alts.size()==1 ) c = new LL1StarBlockSingleAlt(this, ebnfRoot, alts);
				else c = new LL1StarBlock(this, ebnfRoot, alts);
				break;
			case ANTLRParser.POSITIVE_CLOSURE :
				if ( alts.size()==1 ) c = new LL1PlusBlockSingleAlt(this, ebnfRoot, alts);
				else c = new LL1PlusBlock(this, ebnfRoot, alts);
				break;
		}
		return c;
	}

	public Choice getLLStarEBNFBlock(GrammarAST ebnfRoot, List<CodeBlockForAlt> alts) {
		int ebnf = 0;
		if ( ebnfRoot!=null ) ebnf = ebnfRoot.getType();
		Choice c = null;
		switch ( ebnf ) {
			case ANTLRParser.OPTIONAL :
				c = new OptionalBlock(this, ebnfRoot, alts);
				break;
			case ANTLRParser.CLOSURE :
				c = new StarBlock(this, ebnfRoot, alts);
				break;
			case ANTLRParser.POSITIVE_CLOSURE :
				c = new PlusBlock(this, ebnfRoot, alts);
				break;
		}
		return c;
	}

	public List<SrcOp> getLL1Test(IntervalSet look, GrammarAST blkAST) {
		return list(new TestSetInline(this, blkAST, look));
	}

	public boolean needsImplicitLabel(GrammarAST ID, LabeledOp op) {
		return	op.getLabels().size()==0 &&
				(getCurrentAlt().tokenRefsInActions.containsKey(ID.getText()) ||
				getCurrentAlt().ruleRefsInActions.containsKey(ID.getText()));
	}

	// AST REWRITE


	@Override
	public TreeRewrite treeRewrite(GrammarAST ast) {
		TreeRewrite tr = new TreeRewrite(this, getTreeLevel(), getCodeBlockLevel());
		tr.addLocalDecl(new RootDecl(this, 0));
		List<GrammarAST> refs =
			UseDefAnalyzer.getElementReferencesShallowInOuterAlt(getGrammar(), ast);
		refs = UseDefAnalyzer.filterForRuleAndTokenRefs(getCurrentAlt(), refs);
		if ( refs!=null ) {
			for (GrammarAST ref : refs) {
				RewriteIteratorDecl d = new RewriteIteratorDecl(this, ref, getCodeBlockLevel());
				tr.addLocalDecl(d);
				RewriteIteratorInit init = new RewriteIteratorInit(this, d);
				tr.addPreambleOp(init);
			}
		}
		return tr;
	}

	@Override
	public RewriteTreeOptional rewrite_optional(GrammarAST ast) {
		RewriteTreeOptional o =
			new RewriteTreeOptional(this, ast, getTreeLevel(), getCodeBlockLevel());
		List<GrammarAST> refs = UseDefAnalyzer.getElementReferencesInEBNF(getGrammar(),
																		  ast,
																		  true);
		refs = UseDefAnalyzer.filterForRuleAndTokenRefs(getCurrentAlt(), refs);
		if ( refs!=null ) {
			for (GrammarAST ref : refs) {
				RewriteIteratorDecl d = new RewriteIteratorDecl(this, ref, getCodeBlockLevel());
				o.addLocalDecl(d);
				o.conditionalDecls.add(d);
				RewriteIteratorInit init = new RewriteIteratorInit(this, d);
				o.addPreambleOp(init);
			}
		}
		return o;
	}

	@Override
	public RewriteTreeClosure rewrite_closure(GrammarAST ast) {
		RewriteTreeClosure c =
			new RewriteTreeClosure(this, ast, getTreeLevel(), getCodeBlockLevel());
		List<GrammarAST> refs = UseDefAnalyzer.getElementReferencesInEBNF(getGrammar(),
																		  ast,
																		  false);
		refs = UseDefAnalyzer.filterForRuleAndTokenRefs(getCurrentAlt(), refs);
		if ( refs!=null ) {
			for (GrammarAST ref : refs) {
				RewriteIteratorDecl d = new RewriteIteratorDecl(this, ref, getCodeBlockLevel());
				c.addLocalDecl(d);
				c.iteratorDecls.add(d);
				RewriteIteratorInit init = new RewriteIteratorInit(this, d);
				c.addPreambleOp(init);
			}
		}
		return c;
	}

	@Override
	public RewriteTreeStructure rewrite_tree(GrammarAST root) {
		RewriteTreeStructure t = new RewriteTreeStructure(this, root, getTreeLevel(), getCodeBlockLevel());
		t.addLocalDecl( new RootDecl(this, getTreeLevel()) );
		return t;
	}

	public List<SrcOp> rewrite_ruleRef(GrammarAST ID, boolean isRoot) {
		String rootName = gen.target.getRootName(getTreeLevel());
		RewriteRuleRef ruleRef;
		String iterName = gen.target.getRewriteIteratorName(ID, getCodeBlockLevel());
		if ( isRoot ) ruleRef = new RewriteRuleRefIsRoot(this, ID, rootName, iterName);
		else ruleRef = new RewriteRuleRef(this, ID, rootName, iterName);
		return list(ruleRef);
	}

	public List<SrcOp> rewrite_tokenRef(GrammarAST ID, boolean isRoot, ActionAST argAST) {
		Alternative alt = getCurrentAlt();
		String rootName = gen.target.getRootName(getTreeLevel());
		String iterName = gen.target.getRewriteIteratorName(ID, getCodeBlockLevel());
		// not ref'd on left hand side or it is but we have an argument like ID["x"]
		// implies create new node
		if ( alt.tokenRefs.get(ID.getText())==null || argAST!=null ) {
			RewriteImagTokenRef tokenRef;
			if ( isRoot ) {
				tokenRef = new RewriteImagTokenRefIsRoot(this, ID, rootName,
														 ID.getText(), argAST);
			}
			else {
				tokenRef = new RewriteImagTokenRef(this, ID, rootName,
												   ID.getText(), argAST);
			}
			return list(tokenRef);
		}
		// must be token ref on left of ->
		RewriteTokenRef tokenRef;
		if ( isRoot ) tokenRef = new RewriteTokenRefIsRoot(this, ID, rootName, iterName);
		else tokenRef = new RewriteTokenRef(this, ID, rootName, iterName);
		return list(tokenRef);
	}

	@Override
	public List<SrcOp> rewrite_labelRef(GrammarAST ID, boolean isRoot) {
		String rootName = gen.target.getRootName(getTreeLevel());
		String iterName = gen.target.getRewriteIteratorName(ID, getCodeBlockLevel());
		if ( ID.getText().equals(getCurrentRuleFunction().rule.name) ) { // $e in rule e
			RewriteSelfRuleLabelRef labelRef;
			if ( isRoot ) labelRef = new RewriteSelfRuleLabelRef(this, ID, rootName);
			else labelRef = new RewriteSelfRuleLabelRef(this, ID, rootName);
			return list(labelRef);
		}
		else { // normal element label
			RewriteLabelRef labelRef;
			if ( isRoot ) labelRef = new RewriteLabelRefIsRoot(this, ID, rootName, iterName);
			else labelRef = new RewriteLabelRef(this, ID, rootName, iterName);
			return list(labelRef);
		}
	}

	@Override
	public List<SrcOp> rewrite_action(ActionAST actionAST, boolean isRoot) {
		String rootName = gen.target.getRootName(getTreeLevel());
		RewriteAction action;
		if ( isRoot ) action = new RewriteActionIsRoot(this, actionAST, rootName);
		else action = new RewriteAction(this, actionAST, rootName);
		return list(action);
	}

	// support

	public void defineImplicitLabel(GrammarAST ID, LabeledOp op) {
		Decl d;
		Rule r = g.getRule(ID.getText());
		if ( r!=null ) {
			String implLabel = gen.target.getImplicitRuleLabel(ID.getText());
			String ctxName = gen.target.getRuleFunctionContextStructName(r);
			d = new RuleContextDecl(this, implLabel, ctxName);
			((RuleContextDecl)d).isImplicit = true;
		}
		else {
			String implLabel = gen.target.getImplicitTokenLabel(ID.getText());
			d = new TokenDecl(this, implLabel);
			((TokenDecl)d).isImplicit = true;
		}
		op.getLabels().add(d);
		getCurrentRuleFunction().addLocalDecl(d);
	}

	public AddToLabelList getListLabelIfPresent(LabeledOp op, GrammarAST label) {
		AddToLabelList labelOp = null;
		if ( label!=null && label.parent.getType()==ANTLRParser.PLUS_ASSIGN ) {
			String listLabel = gen.target.getListLabel(label.getText());
			labelOp = new AddToLabelList(this, listLabel, op.getLabels().get(0));
		}
		return labelOp;
	}

}
