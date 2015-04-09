/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.databinding.tool;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;

import android.databinding.parser.BindingExpressionLexer;
import android.databinding.parser.BindingExpressionParser;
import android.databinding.tool.expr.Expr;
import android.databinding.tool.expr.ExprModel;
import android.databinding.tool.util.L;

public class ExpressionParser {
    final ExprModel mModel;
    final ExpressionVisitor visitor;

    public ExpressionParser(ExprModel model) {
        mModel = model;
        visitor = new ExpressionVisitor(mModel);
    }

    public Expr parse(String input) {
        ANTLRInputStream inputStream = new ANTLRInputStream(input);
        BindingExpressionLexer lexer = new BindingExpressionLexer(inputStream);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        BindingExpressionParser parser = new BindingExpressionParser(tokenStream);
        BindingExpressionParser.BindingSyntaxContext root = parser.bindingSyntax();
        L.d("exp tree: %s", root.toStringTree(parser));
        return root.accept(visitor);
    }

    public ExprModel getModel() {
        return mModel;
    }
}