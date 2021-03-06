package edu.cs.ucla.preprocess;

import java.awt.Point;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;

import edu.cs.ucla.model.APICall;
import edu.cs.ucla.model.ControlConstruct;
import edu.cs.ucla.model.Item;
import edu.cs.ucla.model.MethodSignature;

public class Preprocess {
	// Config this first!
	boolean isFirstRun = true;

	String path;
	String focal;
	HashMap<String, HashMap<String, String>> types;
	HashMap<String, ArrayList<Item>> seqs;
	HashMap<String, HashSet<MethodSignature>> oracle;

	// supplement the names and types of the receiver, argument(s), and return
	// value of the focal API here
	static ArrayList<ArrayList<String>> argNameList;
	static ArrayList<ArrayList<String>> argTypeList;
	static String rcvName;
	static String rcvType;
	static String retName;
	static String retType;

	public Preprocess(String input, String api) {
		this.path = input;
		this.focal = api;
		this.types = new HashMap<String, HashMap<String, String>>();
		this.seqs = new HashMap<String, ArrayList<Item>>();
		this.oracle = new HashMap<String, HashSet<MethodSignature>>();
	}

	public void process() {
		// first construct the symbol table
		constructSymbolTables();

		// then process the method call sequences
		constructMethodCallSequences();
		
		// construct the oracle of API signatures from the partially resolved api call sequences
		constructAPIOracle();
	}

	private void constructSymbolTables() {
		File f = new File(path + File.separator + "1.txt");
		try (BufferedReader br = new BufferedReader(new FileReader(f))) {
			String line = null;
			while ((line = br.readLine()) != null) {
				if (line.startsWith("vartypes[")) {
					String key = line.substring(line.indexOf("[") + 1,
							line.indexOf("] ="));
					String s = line.substring(line.indexOf("] =") + 3).trim();
					String[] ss = s.split("\\|");
					HashMap<String, String> map = new HashMap<String, String>();
					// skip the first element because it is empty string
					for (int i = 1; i < ss.length; i++) {
						String name = ss[i].split(":")[0];
						String type = ss[i].split(":")[1];
						map.put(name, type);
					}
					types.put(key, map);
				}
			}
		} catch (FileNotFoundException e1) {
			e1.printStackTrace();
		} catch (IOException e1) {
			e1.printStackTrace();
		}
	}

	private void constructMethodCallSequences() {
		File f = new File(path + File.separator + "1-clean.txt");
		try (BufferedReader br = new BufferedReader(new FileReader(f))) {
			String line = null;
			while ((line = br.readLine()) != null) {
				// process each line based on the strategy
				if (line.startsWith("results[")) {
					// set a threshold to avoid processing long methods
					if (line.length() < 3000) {
						String key = line.substring(line.indexOf("[") + 1,
								line.indexOf("][SEQ]"));
						System.out.println("processing " + key);
//						 if(key.equals("https://github.com/CyanMobile/android_frameworks_base_cyanmobile!core/java/android/webkit/WebViewDatabase.java!WebViewDatabase!hasCache"))
//						 {
//							 System.out.println("Hit");
//						 }
						if (types.containsKey(key)) {
							HashMap<String, String> symbol_table = types
									.get(key);
							String seq = line
									.substring(line.indexOf("] =") + 3).trim();
							ArrayList<Item> sequence = new ArrayList<Item>();

							ArrayList<String> ss = ProcessUtils
									.splitByArrow(seq);
							for (String s : ss) {
								s = s.trim();
								int count1 = 0;
								if (s.endsWith("}")) {
									while (s.endsWith("}")) {
										s = s.substring(0, s.lastIndexOf("}"))
												.trim();
										count1++;
									}
								}

								ArrayList<String> rest = new ArrayList<String>();
								while (s.endsWith("} ELSE {")
										|| (s.contains("} CATCH(") && s
												.endsWith(") {"))
										|| s.endsWith("} FINALLY {")) {
									String s1 = s.substring(0,
											s.lastIndexOf('}') + 1).trim();
									String s2 = s.substring(
											s.lastIndexOf('}') + 1, s.length())
											.trim();

									if (!s2.isEmpty()) {
										rest.add(s2);
									}

									while (s1.endsWith("}")) {
										s1 = s1.substring(0,
												s1.lastIndexOf("}")).trim();
										rest.add("}");
									}

									s = s1;

								}

								if (!s.isEmpty()) {
									sequence.addAll(ProcessUtils.extractItems(
											s, symbol_table));
								}

								for (int j = rest.size() - 1; j >= 0; j--) {
									String r = rest.get(j);
									if (r.equals("IF {") || r.equals("ELSE {")
											|| r.equals("TRY {")
											|| r.equals("LOOP {")
											|| r.equals("FINALLY {")
											|| r.equals("}")) {
										ControlConstruct cc = new ControlConstruct(
												r.trim(), null);
										sequence.add(cc);
									} else if (r.contains("CATCH(")
											&& r.endsWith(") {")) {
										String type = r.substring(
												r.lastIndexOf("CATCH(") + 6,
												r.lastIndexOf(") {"));
										ControlConstruct cc = new ControlConstruct(
												"CATCH", type);
										sequence.add(cc);
									}
								}

								while (count1 > 0) {
									sequence.add(new ControlConstruct("}", null));
									count1--;
								}
							}

							this.seqs.put(key, sequence);
						}
					}
				}
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public void constructAPIOracle() {
		for (String key : this.seqs.keySet()) {
			if (types.containsKey(key)) {
				HashMap<String, String> symbol_table = types
						.get(key);
				ArrayList<Item> seq = this.seqs.get(key);
				for(Item item : seq) {
					if(item instanceof APICall) {
						APICall call = (APICall)item;
						String rcv = call.receiver;
						if(!symbol_table.containsKey(rcv)) {
							continue;
						}
						
						String rcvType = symbol_table.get(rcv);
						String name = call.name.substring(0, call.name.indexOf('('));
	    				String sub = call.name.substring(call.name.indexOf('(') + 1, call.name.indexOf(')'));
	    				if(!sub.contains("*")) {
	    					HashSet<MethodSignature> set;
	    					if(oracle.containsKey(name)) {
	    						set = oracle.get(name);
	    					} else {
	    						set = new HashSet<MethodSignature>();
	    					}
	    					
	    					ArrayList<String> argType = new ArrayList<String>();
	    					if(!sub.isEmpty()) {
	    						String[] args = sub.split(",");
	    						for(String arg : args) {
	    							argType.add(arg);
	    						}
	    					}
	    					
	    					MethodSignature ms = new MethodSignature(name, rcvType, argType);
	    					if(set.contains(ms)) {
	    						for(MethodSignature sign : set) {
	    							if(sign.equals(ms)) {
	    								sign.count ++;
	    							}
	    						}
	    					} else {
	    						set.add(ms);
	    					}
	    					
	    					oracle.put(name, set);
	    				}
					}
				}
			}
		}
	}

	public String synthesizeReableCode(ArrayList<APICall> calls,
			HashMap<String, String> map) {
		String code = "";
		HashMap<String, String> dict = new HashMap<String, String>();
		for (int i = 0; i < calls.size(); i++) {
			APICall call = calls.get(i);
			String apiName = call.name.substring(0, call.name.indexOf('('));
			if (!call.ret.contains(apiName + "(")) {
				// this call has a return value and its return value is assigned
				// to another variable
				String retType = "";
				if (map.containsKey(call.ret)) {
					retType = map.get(call.ret);
					code += retType + " " + retType.toLowerCase() + " = ";
				} else {
					// cannot resolve the type of the lefthand side variable
					code += call.ret + " = ";
				}
			} else {
				// this call either does not have a return value or its return
				// value is immediately consumed by another call
				// scan the succeeding API calls and check
				boolean isConsumed = false;
				for (int j = i + 1; j < calls.size(); j++) {
					APICall next = calls.get(j);
					if (next.arguments.contains(call.ret)
							|| (next.receiver != null && next.receiver
									.contains(call.ret))) {
						// yes
						isConsumed = true;
						break;
					}
				}
				if (isConsumed) {
					// introduce a temporary variable to store its value
					code += "value = ";
					// put this temporary variable name in the dictionary
					dict.put(call.ret, "value");
				}
			}

			if (call.receiver != null) {
				if (map.containsKey(call.receiver)) {
					String rcvType = map.get(call.receiver);
					code += rcvType.toLowerCase() + "." + apiName + "(";
				} else if (dict.containsKey(call.receiver)) {
					String temp = dict.get(call.receiver);
					code += temp + "." + apiName + "(";
				} else {
					code += call.receiver + "." + apiName + "(";
				}
			} else {
				code += apiName + "(";
			}

			if (!call.arguments.isEmpty()) {
				for (String argument : call.arguments) {
					if (map.containsKey(argument)) {
						String argType = map.get(argument);
						code += argType.toLowerCase() + ", ";
					} else if (dict.containsKey(argument)) {
						String temp = dict.get(argument);
						code += temp + ", ";
					} else {
						code += argument + ", ";
					}
				}
				code = code.substring(0, code.length() - 2);
			}

			code += ");" + System.lineSeparator();
		}

		return code;
	}

	public void dumpToJsonNewSchema(String output) {
		if (new File(output).exists()) {
			new File(output).delete();
		}
		FileUtils.appendStringToFile("[", output);

		int id = 0;
		int numOfUnreachableUrls = 0;
		for (String key : this.seqs.keySet()) {
			// download source code from GitHub if not cached already
			String[] ss = key.split("\\!");
			String projectURL = ss[0];
			String projectName = projectURL.substring(19);
			projectName = projectName.replaceAll("\\/", "-");
			String srcPath = ss[1];
			String className = ss[2];
			String methodName = ss[3];
			String url = projectURL + "/tree/master/" + srcPath;
			String dumpPath = path + File.separator + "dump" + File.separator
					+ projectName + File.separator + className + ".java";
			File dumpFile = new File(dumpPath);
			String method = "empty";
			String code = null;
			if (isFirstRun) {
				// fetch by url
				System.out.println("Fetching code from " + url);
				code = FetchSourceCode.fetchCodeByUrl(url);
				if (code != null) {
					if (!dumpFile.getParentFile().exists()) {
						dumpFile.getParentFile().mkdirs();
					}

					FileUtils.writeStringToFile(code, dumpPath);
				} else {
					numOfUnreachableUrls++;
				}
			} else {
				// fetch from cached file
				if (new File(dumpPath).exists()) {
					code = FileUtils.readFileToString(dumpPath);
				}
			}

			if (code == null) {
				// only printing those examples that we can fetch raw code
				continue;
			}

			// extract the corresponding method from the source code
			ASTParser p = getASTParser(code);
			CompilationUnit cu = (CompilationUnit) p.createAST(null);
			ArrayList<String> methods = new ArrayList<String>();
			final String src = code;
			cu.accept(new ASTVisitor() {
				@Override
				public boolean visit(MethodDeclaration node) {
					if (node.getName().toString().equals(methodName)) {
						int startLine = cu.getLineNumber(node
								.getStartPosition()) - 1;
						int endLine = cu.getLineNumber(node.getStartPosition()
								+ node.getLength()) - 1;
						String s = "";
						String[] ss = src.split(System.lineSeparator());
						for (int i = startLine; i <= endLine; i++) {
							s += ss[i] + System.lineSeparator();
						}
						methods.add(s);
					}

					return false;
				}
			});

			for (String m : methods) {
				if (m.contains(focal + "(")) {
					method = m;
					break;
				}
			}

			if (method.equals("empty")) {
				// only printing those code examples that we can find the
				// matching method
				continue;
			}

			// get focal API call
			ArrayList<Item> seq = this.seqs.get(key);
			APICall theCall = null;
			ArrayList<APICall> inits = new ArrayList<APICall>();
			ArrayList<APICall> configs = new ArrayList<APICall>();
			ArrayList<APICall> uses = new ArrayList<APICall>();
			int indexOfFocal = -1;
			ArrayList<String> argNames = null;
			ArrayList<String> argTypes = null;
			for (int i = 0; i < seq.size(); i++) {
				Item item = seq.get(i);
				if (item instanceof APICall) {
					String signature = ((APICall) item).name;
					if (signature.contains(focal + "(")) {
						// this is likely to be the focal API call
						if(signature.contains("*")) {
							signature = getSignature(key, ((APICall) item));
						}
						 
						// check for overloading methods for the focal API
						for(int j = 0; j < argTypeList.size(); j++) {
							ArrayList<String> l1 = argTypeList.get(j);
							if(l1.size() == ((APICall) item).arguments.size()) {
								boolean isMatch = true;

								String sub = signature.substring(signature.indexOf('(') + 1, signature.indexOf(')'));
								if (!sub.isEmpty()) {
									String[] subs = sub.split(",");
									for(int k = 0; k < l1.size(); k ++) {
										String arg1 = l1.get(k);
										String arg2 = subs[k];
										if(arg1.equals("*") || arg2.equals("*") || arg1.equals(arg2)) {
											continue;
										} else {
											isMatch = false;
											break;
										}
									}
								}
								
								if(isMatch) {
									// match
									argNames = argNameList.get(j);
									argTypes = argTypeList.get(j);
									break;
								}
							}
						}
						
						if(argNames == null) {
							// this is not the focal API call
							continue;
						} else {
							// find a match in the supplemented signatures
							theCall = (APICall) item;
							indexOfFocal = i;
						}

						// scanning backward to find the first preceding call
						for (int j = i - 1; j >= 0; j--) {
							Item prev = seq.get(j);
							if (prev instanceof APICall) {
								APICall call = (APICall) prev;
								if (call.receiver != null
										&& (call.receiver
												.equals(theCall.receiver) || theCall.arguments
												.contains(call.receiver))) {
									// this call is invoked on the same receiver
									// object as the focal API call
									// or
									// / this call is invoked on one of the
									// arguments of the focal API call
									configs.add(0, call);
								}

								if (theCall.arguments.contains(call.ret)
										|| (theCall.receiver != null && theCall.receiver
												.equals(call.ret))) {
									// this call's return value is either the
									// arguments or the receiver of the focal
									// API
									// call
									inits.add(0, call);
								}
							}
						}

						// scanning forward to find the first succeeding call
						for (int j = i + 1; j < seq.size(); j++) {
							Item post = seq.get(j);
							if (post instanceof APICall) {
								APICall call = (APICall) post;
								if (call.receiver != null
										&& call.receiver
												.equals(theCall.receiver)) {
									// this call is invoked on the same receiver
									// object as the focal API call
									uses.add(call);
								} else if (call.arguments.contains(theCall.ret)) {
									// this call uses the return value of the
									// focal API call as its argument
									uses.add(call);
								} else if (call.receiver != null
										&& call.receiver.equals(theCall.ret)) {
									// this call is invoked on the return value
									// of the
									// focal API call
									uses.add(call);
								} else if (theCall.receiver != null
										&& call.arguments
												.contains(theCall.receiver)) {
									// this call uses the receiver of the focal
									// API as its argument
									uses.add(call);
								}
							}
						}

						break;
					}
				}
			}

			if (theCall == null) {
				continue;
			}

			// check whether it has exception handling
			ControlConstruct tryBlock = null;
			for (int i = indexOfFocal - 1; i >= 0; i--) {
				Item item = seq.get(i);
				if (item instanceof ControlConstruct) {
					ControlConstruct cc = (ControlConstruct) item;
					if (cc.type.equals("TRY {")) {
						tryBlock = cc;
						break;
					}
				}
			}

			ControlConstruct catchBlock = null;
			ArrayList<APICall> exceptionCalls = new ArrayList<APICall>();
			if (tryBlock != null) {
				// search for the catch block
				boolean inCatchBlock = false;
				for (int i = indexOfFocal + 1; i < seq.size(); i++) {
					Item item = seq.get(i);
					if (item instanceof ControlConstruct) {
						ControlConstruct cc = (ControlConstruct) item;
						if (cc.type.equals("CATCH")) {
							catchBlock = cc;
							inCatchBlock = true;
						} else if (inCatchBlock && cc.type.equals("}")) {
							inCatchBlock = false;
							break;
						}
					} else if (item instanceof APICall) {
						APICall call = (APICall) item;
						if (inCatchBlock) {
							exceptionCalls.add(call);
						}
					}
				}
			}

			// check for finally blocks
			ControlConstruct finallyBlock = null;
			ArrayList<APICall> cleanUpCalls = new ArrayList<APICall>();
			boolean inFinallyBlock = false;
			for (int i = indexOfFocal + 1; i < seq.size(); i++) {
				Item item = seq.get(i);
				if (item instanceof ControlConstruct) {
					ControlConstruct cc = (ControlConstruct) item;
					if (cc.type.equals("FINALLY {")) {
						finallyBlock = cc;
						inFinallyBlock = true;
					} else if (inFinallyBlock && cc.type.equals("}")) {
						inFinallyBlock = false;
						break;
					}
				} else if (item instanceof APICall) {
					APICall call = (APICall) item;
					if (inFinallyBlock) {
						cleanUpCalls.add(call);
					}
				}
			}

			// now we can match elements and also extract guard condition
			// block/follow-up check
			ArrayList<APICall> apiCalls = new ArrayList<APICall>();
			apiCalls.addAll(inits);
			apiCalls.addAll(configs);
			apiCalls.add(theCall);
			apiCalls.addAll(uses);

			ArrayList<ControlConstruct> controlConstructs = new ArrayList<ControlConstruct>();
			if (tryBlock != null && catchBlock != null) {
				controlConstructs.add(tryBlock);
				controlConstructs.add(catchBlock);
				if (!exceptionCalls.isEmpty()) {
					apiCalls.addAll(exceptionCalls);
				}
			}

			if (finallyBlock != null) {
				controlConstructs.add(finallyBlock);
				if (!cleanUpCalls.isEmpty()) {
					apiCalls.addAll(cleanUpCalls);
				}
			}

			MatchCodeElements matcher = new MatchCodeElements(methodName,
					theCall, apiCalls, controlConstructs);
			cu.accept(matcher);

			// debug the location finder
			// int offset = code.indexOf(method);
			// for (APICall call : apiCalls) {
			// System.out.println(call);
			// if (matcher.callRanges.containsKey(call)) {
			// ArrayList<Point> ranges = matcher.callRanges.get(call);
			// for (Point range : ranges) {
			// System.out.println(method.substring(range.x - offset,
			// range.y - offset));
			// }
			// } else {
			// System.out.println("Not forund");
			// }
			// }
			//
			// for (ControlConstruct cc : controlConstructs) {
			// System.out.println(cc);
			// if (matcher.controlRanges.containsKey(cc)) {
			// ArrayList<Pair<Point, Point>> ranges = matcher.controlRanges
			// .get(cc);
			// for (Pair<Point, Point> range : ranges) {
			// System.out.println(method.substring(range.getLeft().x
			// - offset, range.getLeft().y - offset));
			// }
			// } else {
			// System.out.println("Not found");
			// }
			// }
			//
			// if(!theCall.normalizedGuard.equals("true")) {
			// if(matcher.guardBlock != null) {
			// System.out.println("Guard Block---" +
			// method.substring(matcher.guardBlock.startIndex1
			// - offset, matcher.guardBlock.endIndex1 - offset));
			// } else {
			// System.out.println("Guard not found---" +
			// theCall.normalizedGuard);
			// }
			// }
			//
			//
			// if(matcher.followUpCheck != null) {
			// System.out.println("Value Check Block---" +
			// method.substring(matcher.followUpCheck.startIndex1
			// - offset, matcher.followUpCheck.endIndex1 - offset));
			// }

			int offset = code.indexOf(method);
			// find where the focal API call is
			int theCallStart = -1;
			int theCallEnd = -1;
			if (matcher.callRanges.containsKey(theCall)) {
				ArrayList<Point> range = matcher.callRanges.get(theCall);
				theCallStart = range.get(0).x;
				theCallEnd = range.get(0).y;
			} else {
				// TODO: cannot match the focal API, discard this example
				continue;
			}

			StringBuilder sb2 = new StringBuilder();
			sb2.append("{\"exampleID\": " + id + ", ");

			// dump the initialization API calls
			sb2.append("\"initialization\":[");
			if (!inits.isEmpty()) {
				String s = "";
				for (APICall call : inits) {
					String signature = getSignature(key, call);
					// synthesize the initialization statement with the provided
					// names and types
					// of receiver and arguments for better readability
					if (call.ret.equals(theCall.receiver)) {
						if(rcvName != null && rcvType != null) {
							s += "\"" + rcvType + " " + rcvName + " = " + signature
									+ "\", ";
						} else {
							// the focal API is a constructor call, should not have receiver objects
							s += "\"" + signature + "\", ";
						}
					} else {
						// check which argument this is
						int pos = theCall.arguments.indexOf(call.ret);
						s += "\"" + argTypes.get(pos) + " " + argNames.get(pos) + " = " + signature
								+ "\", ";
					}
				}
				sb2.append(s.substring(0, s.length() - 2));
			}

			sb2.append("], ");

			if (!inits.isEmpty()) {
				ArrayList<Point> ranges = getAPICallRangesBeforeFocal(
						theCallStart, inits, matcher.callRanges, offset);

				// check for unmatched API calls caused by source code
				// inconsistency between Boa and GitHub
				boolean inValid = false;
				for (Point range : ranges) {
					if (range.x == -1 || range.y == -1) {
						inValid = true;
						break;
					}
				}
				if (inValid) {
					continue;
				}

				sb2.append("\"initializationStart\":[");
				String starts = "";
				for (int i = 0; i < inits.size(); i++) {
					starts += ranges.get(i).x + ", ";
				}
				sb2.append(starts.substring(0, starts.length() - 2));
				sb2.append("], ");

				sb2.append("\"initializationEnd\":[");
				String ends = "";
				for (int i = 0; i < inits.size(); i++) {
					ends += ranges.get(i).y + ", ";
				}

				sb2.append(ends.substring(0, ends.length() - 2));
				sb2.append("], ");
			} else {
				sb2.append("\"initializationStart\":[], ");
				sb2.append("\"initializationEnd\":[], ");
			}

			// dump the json key-value pairs related to exception handling
			// Update on 09/07/2017: we decide not to render the thrown
			// exceptions that are
			// declared in a method header, but rather focus on these exceptions
			// that are handled
			// explicitly and locally in a try-catch block
			int tryExpressionStart = -1;
			int tryExpressionEnd = -1;
			int tryBlockStart = -1;
			int tryBlockEnd = -1;
			int catchExpressionStart = -1;
			int catchExpressionEnd = -1;
			int catchBlockStart = -1;
			int catchBlockEnd = -1;
			if (tryBlock != null && catchBlock != null
					&& matcher.controlRanges.containsKey(tryBlock)
					&& matcher.controlRanges.containsKey(catchBlock)) {
				ArrayList<Pair<Point, Point>> ranges1 = matcher.controlRanges
						.get(tryBlock);
				Pair<Point, Point> range1 = null;
				int delta1 = Integer.MAX_VALUE;
				for (Pair<Point, Point> pair : ranges1) {
					int diff = theCallStart - pair.getLeft().x;
					if (diff < delta1 && diff >= 0) {
						range1 = pair;
						delta1 = diff;
					}
				}

				if (range1 != null) {
					tryExpressionStart = range1.getLeft().x - offset;
					tryExpressionEnd = range1.getLeft().y - offset;
					tryBlockStart = range1.getRight().x - offset;
					tryBlockEnd = range1.getRight().y - offset;
				}

				ArrayList<Pair<Point, Point>> ranges2 = matcher.controlRanges
						.get(catchBlock);
				Pair<Point, Point> range2 = null;
				int delta2 = Integer.MAX_VALUE;
				for (Pair<Point, Point> pair : ranges2) {
					int diff = pair.getLeft().x - theCallStart;
					if (diff < delta2 && diff >= 0) {
						range2 = pair;
						delta2 = diff;
					}
				}

				if (range2 != null) {
					catchExpressionStart = range2.getLeft().x - offset;
					catchExpressionEnd = range2.getLeft().y - offset;
					catchBlockStart = range2.getRight().x - offset;
					catchBlockEnd = range2.getRight().y - offset;
				}
			}

			if (tryExpressionStart != -1 && catchExpressionStart != -1) {
				sb2.append("\"hasTryCatch\": 1, ");
				sb2.append("\"exceptionType\": \"" + catchBlock.guard + "\", ");

				sb2.append("\"exceptionHandlingCall\": [");
				if (!exceptionCalls.isEmpty()) {
					String s = "";
					for (APICall call : exceptionCalls) {
						String signature = getSignature(key, call);
						s += "\"" + signature + "\", ";
					}
					sb2.append(s.substring(0, s.length() - 2));
				}
				sb2.append("], ");

				sb2.append("\"tryExpressionStart\": " + tryExpressionStart
						+ ", ");
				sb2.append("\"tryExpressionEnd\": " + tryExpressionEnd + ", ");
				sb2.append("\"tryBlockStart\": " + tryBlockStart + ", ");
				sb2.append("\"tryBlockEnd\": " + tryBlockEnd + ", ");

				sb2.append("\"catchExpressionStart\": " + catchExpressionStart
						+ ", ");
				sb2.append("\"catchExpressionEnd\": " + catchExpressionEnd
						+ ", ");
				sb2.append("\"catchBlockStart\": " + catchBlockStart + ", ");
				sb2.append("\"catchBlockEnd\": " + catchBlockEnd + ", ");

				if (!exceptionCalls.isEmpty()) {
					ArrayList<Point> ranges = getAPICallRangesAfterFocal(
							theCallEnd, exceptionCalls, matcher.callRanges,
							offset);

					// check for unmatched API calls caused by source code
					// inconsistency between Boa and GitHub
					boolean inValid = false;
					for (Point range : ranges) {
						if (range.x == -1 || range.y == -1) {
							inValid = true;
							break;
						}
					}
					if (inValid) {
						continue;
					}

					sb2.append("\"exceptionHandlingCallStart\":[");
					String starts = "";
					for (int i = 0; i < exceptionCalls.size(); i++) {
						starts += ranges.get(i).x + ", ";
					}
					sb2.append(starts.substring(0, starts.length() - 2));
					sb2.append("], ");

					sb2.append("\"exceptionHandlingCallEnd\":[");
					String ends = "";
					for (int i = 0; i < exceptionCalls.size(); i++) {
						ends += ranges.get(i).y + ", ";
					}

					sb2.append(ends.substring(0, ends.length() - 2));
					sb2.append("], ");
				} else {
					sb2.append("\"exceptionHandlingCallStart\":[], ");
					sb2.append("\"exceptionHandlingCallEnd\":[], ");
				}
			} else {
				sb2.append("\"hasTryCatch\": 0, ");
				sb2.append("\"exceptionType\": \"empty\", ");
				sb2.append("\"exceptionHandlingCall\": [], ");
				sb2.append("\"tryExpressionStart\": -1, ");
				sb2.append("\"tryExpressionEnd\": -1, ");
				sb2.append("\"tryBlockStart\": -1, ");
				sb2.append("\"tryBlockEnd\": -1, ");
				sb2.append("\"catchExpressionStart\": -1, ");
				sb2.append("\"catchExpressionEnd\": -1, ");
				sb2.append("\"catchBlockStart\": -1, ");
				sb2.append("\"catchBlockEnd\": -1, ");
				sb2.append("\"exceptionHandlingCallStart\": [], ");
				sb2.append("\"exceptionHandlingCallEnd\": [], ");
			}

			// dump the configuration API calls
			sb2.append("\"configuration\":[");
			if (!configs.isEmpty()) {
				String s = "";
				for (APICall call : configs) {
					String signature = getSignature(key, call);
					signature = signature.substring(signature.indexOf('.') + 1);
					if (call.receiver != null
							&& call.receiver.equals(theCall.receiver)) {
						// augment with the provided receiver object name
						s += "\"" + rcvName + "." + signature + "\", ";
					} else {
						// augment with the provided argument name
						int pos = theCall.arguments.indexOf(call.receiver);
						s += "\"" + argNames.get(pos) + "." + signature + "\", ";
					}
				}
				sb2.append(s.substring(0, s.length() - 2));
			}

			sb2.append("], ");

			if (!configs.isEmpty()) {
				ArrayList<Point> ranges = getAPICallRangesBeforeFocal(
						theCallStart, configs, matcher.callRanges, offset);

				// check for unmatched API calls caused by source code
				// inconsistency between Boa and GitHub
				boolean inValid = false;
				for (Point range : ranges) {
					if (range.x == -1 || range.y == -1) {
						inValid = true;
						break;
					}
				}
				if (inValid) {
					continue;
				}

				sb2.append("\"configurationStart\":[");
				String starts = "";
				for (int i = 0; i < configs.size(); i++) {
					starts += ranges.get(i).x + ", ";
				}
				sb2.append(starts.substring(0, starts.length() - 2));
				sb2.append("], ");

				sb2.append("\"configurationEnd\":[");
				String ends = "";
				for (int i = 0; i < configs.size(); i++) {
					ends += ranges.get(i).y + ", ";
				}

				sb2.append(ends.substring(0, ends.length() - 2));
				sb2.append("], ");
			} else {
				sb2.append("\"configurationStart\":[], ");
				sb2.append("\"configurationEnd\":[], ");
			}

			// dump the guard condition block
			if (matcher.guardBlock != null) {
				String guard = matcher.guardBlock.guard;
				// replace rcv and arg0 with supplemented names
				if(rcvName != null) {
					guard = guard.replaceAll("rcv", rcvName);
				}
				for(int i = 0; i < argNames.size(); i++) {
					if(guard.contains("arg" + i)) {
						guard = guard.replaceAll("arg" + i, argNames.get(i));
					}
				}
				sb2.append("\"guardCondition\": \""
						+ StringEscapeUtils.escapeJava(guard) + "\", ");
				sb2.append("\"guardType\": \"" + matcher.guardBlock.type
						+ "\", ");
				sb2.append("\"guardExpressionStart\": "
						+ (matcher.guardBlock.startIndex1 - offset) + ", ");
				sb2.append("\"guardExpressionEnd\": "
						+ (matcher.guardBlock.endIndex1 - offset) + ", ");
				sb2.append("\"guardBlockStart\": "
						+ (matcher.guardBlock.startIndex2 - offset) + ", ");
				sb2.append("\"guardBlockEnd\": "
						+ (matcher.guardBlock.endIndex2 - offset) + ", ");
			} else {
				sb2.append("\"guardCondition\": \"empty\", ");
				sb2.append("\"guardType\": \"empty\", ");
				sb2.append("\"guardExpressionStart\": -1, ");
				sb2.append("\"guardExpressionEnd\": -1, ");
				sb2.append("\"guardBlockStart\": -1, ");
				sb2.append("\"guardBlockEnd\": -1, ");
			}

			// dump the focal API call
			if(rcvName != null) {
				sb2.append("\"focalAPI\": \"" + retName + " = " + rcvName + "."
						+ focal + "(");
			} else {
				sb2.append("\"focalAPI\": \"" + retName + " = " + focal + "(");
			}
			 
			if(!argNames.isEmpty()) {
				String s = "";
				for(String argName : argNames) {
					s += argName + ",";
				}
				sb2.append(s.substring(0, s.length() - 1));
			}
			sb2.append(")\", ");
			sb2.append("\"focalAPIStart\": " + (theCallStart - offset) + ", ");
			sb2.append("\"focalAPIEnd\": " + (theCallEnd - offset) + ", ");

			// dump the follow-up check on the return value of the focal API
			if (matcher.followUpCheck != null) {
				String check = matcher.followUpCheck.guard;
				if(rcvName != null) {
					check = check.replaceAll("rcv", rcvName);
				}
				for(int i = 0; i < argNames.size(); i++) {
					if(check.contains("arg" + i)) {
						check = check.replaceAll("arg" + i, argNames.get(i));
					}
				}
				check = check.replaceAll("ret", retName);

				sb2.append("\"followUpCheck\": \""
						+ StringEscapeUtils.escapeJava(check) + "\", ");
				sb2.append("\"checkType\": \"" + matcher.followUpCheck.type
						+ "\", ");
				sb2.append("\"followUpCheckExpressionStart\": "
						+ (matcher.followUpCheck.startIndex1 - offset) + ", ");
				sb2.append("\"followUpCheckExpressionEnd\": "
						+ (matcher.followUpCheck.endIndex1 - offset) + ", ");
				sb2.append("\"followUpCheckBlockStart\": "
						+ (matcher.followUpCheck.startIndex2 - offset) + ", ");
				sb2.append("\"followUpCheckBlockEnd\": "
						+ (matcher.followUpCheck.endIndex2 - offset) + ", ");
			} else {
				sb2.append("\"followUpCheck\": \"empty\", ");
				sb2.append("\"checkType\": \"empty\", ");
				sb2.append("\"followUpCheckExpressionStart\": -1, ");
				sb2.append("\"followUpCheckExpressionEnd\": -1, ");
				sb2.append("\"followUpCheckBlockStart\": -1, ");
				sb2.append("\"followUpCheckBlockEnd\": -1, ");
			}

			// dump the API calls that use the return value or the receiver
			// object of the focal API call
			sb2.append("\"use\":[");
			if (!uses.isEmpty()) {
				String s = "";
				for (APICall call : uses) {
					String signature = getSignature(key, call);
					if (call.receiver != null
							&& call.receiver.equals(theCall.receiver)) {
						// this call is invoked on the same receiver as the
						// focal API call
						signature = signature.substring(signature.indexOf('.') + 1);
						s += "\"" + rcvName + "." + signature + "\", ";
					} else if (call.arguments.contains(theCall.ret)) {
						// this call uses the return value of the focal API call
						// as one of its arguments
						int index = call.arguments.indexOf(theCall.ret);
						String args = signature.substring(
								signature.indexOf('(') + 1,
								signature.indexOf(')'));
						String[] argss = args.split(",");
						argss[index] = retName;
						String normalizedUseCall = signature.substring(0,
								signature.indexOf('(')) + "(";
						for (String arg : argss) {
							normalizedUseCall += arg + ",";
						}
						normalizedUseCall = normalizedUseCall.substring(0,
								normalizedUseCall.length() - 1) + ")";

						s += "\"" + normalizedUseCall + "\", ";
					} else if (call.receiver != null
							&& call.receiver.equals(theCall.ret)) {
						// this call is invoked on the return value of the focal
						// API call
						signature = signature.substring(signature.indexOf('.') + 1);
						s += "\"" + retName + "." + signature + "\", ";
					} else {
						// this call uses the receiver object of the focal API
						// call as one of its arguments
						int index = call.arguments.indexOf(theCall.receiver);
						String args = signature.substring(
								signature.indexOf('(') + 1,
								signature.indexOf(')'));
						String[] argss = args.split(",");
						argss[index] = rcvName;
						String normalizedUseCall = signature.substring(0,
								signature.indexOf('(')) + "(";
						for (String arg : argss) {
							normalizedUseCall += arg + ",";
						}
						normalizedUseCall = normalizedUseCall.substring(0,
								normalizedUseCall.length() - 1) + ")";

						s += "\"" + normalizedUseCall + "\", ";
					}
				}
				sb2.append(s.substring(0, s.length() - 2));
			}

			sb2.append("], ");

			if (!uses.isEmpty()) {
				ArrayList<Point> ranges = getAPICallRangesAfterFocal(
						theCallEnd, uses, matcher.callRanges, offset);

				// check for unmatched API calls caused by source code
				// inconsistency between Boa and GitHub
				boolean inValid = false;
				for (Point range : ranges) {
					if (range.x == -1 || range.y == -1) {
						inValid = true;
						break;
					}
				}
				if (inValid) {
					continue;
				}

				sb2.append("\"useStart\":[");
				String starts = "";
				for (int i = 0; i < uses.size(); i++) {
					starts += ranges.get(i).x + ", ";
				}
				sb2.append(starts.substring(0, starts.length() - 2));
				sb2.append("], ");

				sb2.append("\"useEnd\":[");
				String ends = "";
				for (int i = 0; i < uses.size(); i++) {
					ends += ranges.get(i).y + ", ";
				}

				sb2.append(ends.substring(0, ends.length() - 2));
				sb2.append("], ");
			} else {
				sb2.append("\"useStart\":[], ");
				sb2.append("\"useEnd\":[], ");
			}

			// dump the key-value pairs related to the finally block
			if (finallyBlock != null) {
				sb2.append("\"hasFinally\": 1, ");

				sb2.append("\"cleanUpCall\": [");
				if (!cleanUpCalls.isEmpty()) {
					String s = "";
					for (APICall call : cleanUpCalls) {
						String signature = getSignature(key, call);
						s += "\"" + signature + "\", ";
					}
					sb2.append(s.substring(0, s.length() - 2));
				}
				sb2.append("], ");

				int finallyExpressionStart = -1;
				int finallyExpressionEnd = -1;
				int finallyBlockStart = -1;
				int finallyBlockEnd = -1;
				if (matcher.controlRanges.containsKey(finallyBlock)) {
					ArrayList<Pair<Point, Point>> ranges = matcher.controlRanges
							.get(finallyBlock);
					Pair<Point, Point> range = null;
					int delta = Integer.MAX_VALUE;
					for (Pair<Point, Point> pair : ranges) {
						int diff = pair.getLeft().x - theCallStart;
						if (diff < delta && diff >= 0) {
							range = pair;
							delta = diff;
						}
					}

					if (range != null) {
						finallyExpressionStart = range.getLeft().x - offset;
						finallyExpressionEnd = range.getLeft().y - offset;
						finallyBlockStart = range.getRight().x - offset;
						finallyBlockEnd = range.getRight().y - offset;
					}
				}

				sb2.append("\"finallyExpressionStart\": "
						+ finallyExpressionStart + ", ");
				sb2.append("\"finallyExpressionEnd\": " + finallyExpressionEnd
						+ ", ");
				sb2.append("\"finallyBlockStart\": " + finallyBlockStart + ", ");
				sb2.append("\"finallyBlockEnd\": " + finallyBlockEnd + ", ");

				if (!cleanUpCalls.isEmpty()) {
					ArrayList<Point> ranges = getAPICallRangesAfterFocal(
							theCallEnd, cleanUpCalls, matcher.callRanges,
							offset);

					// check for unmatched API calls caused by source code
					// inconsistency between Boa and GitHub
					boolean inValid = false;
					for (Point range : ranges) {
						if (range.x == -1 || range.y == -1) {
							inValid = true;
							break;
						}
					}
					if (inValid) {
						continue;
					}

					sb2.append("\"cleanUpCallStart\":[");
					String starts = "";
					for (int i = 0; i < cleanUpCalls.size(); i++) {
						starts += ranges.get(i).x + ", ";
					}
					sb2.append(starts.substring(0, starts.length() - 2));
					sb2.append("], ");

					sb2.append("\"cleanUpCallEnd\":[");
					String ends = "";
					for (int i = 0; i < cleanUpCalls.size(); i++) {
						ends += ranges.get(i).y + ", ";
					}

					sb2.append(ends.substring(0, ends.length() - 2));
					sb2.append("], ");
				} else {
					sb2.append("\"cleanUpCallStart\":[], ");
					sb2.append("\"cleanUpCallEnd\":[], ");
				}
			} else {
				sb2.append("\"hasFinally\": 0, ");
				sb2.append("\"cleanUpCall\": [], ");
				sb2.append("\"finallyExpressionStart\": -1, ");
				sb2.append("\"finallyExpressionEnd\": -1, ");
				sb2.append("\"finallyBlockStart\": -1, ");
				sb2.append("\"finallyBlockEnd\": -1, ");
				sb2.append("\"cleanUpCallStart\": [], ");
				sb2.append("\"cleanUpCallEnd\": [], ");
			}

			sb2.append("\"url\": \"" + url + "\", ");
			sb2.append("\"rawCode\": \"" + StringEscapeUtils.escapeJava(method)
					+ "\"}");

			FileUtils.appendStringToFile(
					sb2.toString() + "," + System.lineSeparator(), output);

			id++;
		}

		FileUtils.appendStringToFile("]", output);
		// log the number of unreachable urls
		System.out.println(numOfUnreachableUrls);
	}

	private String getSignature(String key, APICall call) {
		if(!types.containsKey(key)) {
			return call.name;
		}
		
		HashMap<String, String> symbol_table = types.get(key);
		String name = call.name.substring(0, call.name.indexOf('('));
		String args = call.name.substring(call.name.indexOf('(') + 1, call.name.indexOf(')'));
		if(call.receiver == null) {
			if (!args.contains("*")) {
				return call.name;
			}
		} else {
			String rcvType = symbol_table.get(call.receiver);
			if(rcvType != null && !args.contains("*")) {
				// all types are resolved
				return rcvType + "." + name + "(" + args + ")";
			}
		}
		
		if(oracle.containsKey(name)) {
			HashSet<MethodSignature> set = oracle.get(name);
			MethodSignature match  = null;
			for(MethodSignature ms : set) {
				if(ms.name.equals(name)) {
					ArrayList<String> arr = new ArrayList<String>();
					String[] ss = args.split(",");
					for(String arg : ss) {
						arr.add(arg);
					}
					
					if(rcvType != null && !rcvType.equals(ms.rcvType)) {
						// not from the same class
						continue;
					}
					
					ArrayList<String> args1 = ms.argType;
					if(arr.size() == args1.size()) {
						boolean isMatch = true;
						for(int i = 0; i < arr.size(); i++) {
							String arg1 = arr.get(i);
							String arg2 = args1.get(i);
							if(!arg1.equals("*") && !arg1.equals(arg2)) {
								isMatch = false;
								break;
							}
						}
						
						if(isMatch) {
							if(match != null) {
								if(ms.count > match.count) {
									match = ms;
								}
							} else {
								match = ms;
							}
						}
					}
				}
			}
			
			if(match != null) {
				return match.toString();
			} else {
				return call.name; 
			}
		} else {
			return call.name;
		}
	}

	private ArrayList<Point> getAPICallRangesBeforeFocal(int focalIndex,
			ArrayList<APICall> calls,
			HashMap<APICall, ArrayList<Point>> callRanges, int offset) {
		int next = focalIndex;
		ArrayList<Point> results = new ArrayList<Point>();
		for (int i = calls.size() - 1; i >= 0; i--) {
			APICall call = calls.get(i);
			if (callRanges.containsKey(call)) {
				ArrayList<Point> ranges = callRanges.get(call);
				Point range = null;
				for (int j = ranges.size() - 1; j >= 0; j--) {
					Point point = ranges.get(j);
					if (point.x < next) {
						range = point;
						break;
					}
				}
				if (range != null) {
					results.add(0,
							new Point(range.x - offset, range.y - offset));
					next = range.x;
				} else {
					results.add(0, new Point(-1, -1));
				}
			} else {
				results.add(0, new Point(-1, -1));
			}
		}
		return results;
	}

	private ArrayList<Point> getAPICallRangesAfterFocal(int focalIndex,
			ArrayList<APICall> calls,
			HashMap<APICall, ArrayList<Point>> callRanges, int offset) {
		int prev = focalIndex;
		ArrayList<Point> results = new ArrayList<Point>();
		for (int i = 0; i < calls.size(); i++) {
			APICall call = calls.get(i);
			if (callRanges.containsKey(call)) {
				ArrayList<Point> ranges = callRanges.get(call);
				Point range = null;
				for (int j = 0; j < ranges.size(); j++) {
					Point point = ranges.get(j);
					if (point.y > prev) {
						range = point;
						break;
					}
				}
				if (range != null) {
					results.add(new Point(range.x - offset, range.y - offset));
					prev = range.y;
				} else {
					results.add(new Point(-1, -1));
				}
			} else {
				results.add(new Point(-1, -1));
			}
		}
		return results;
	}

	public void dumpToJson(String output) {
		if (new File(output).exists()) {
			new File(output).delete();
		}
		FileUtils.appendStringToFile("[", output);

		int id = 0;
		int numOfUnreachableUrls = 0;
		for (String key : this.seqs.keySet()) {
			// get focal API call
			ArrayList<Item> seq = this.seqs.get(key);
			APICall theCall = null;
			ArrayList<APICall> prevCalls = new ArrayList<APICall>();
			ArrayList<APICall> postCalls = new ArrayList<APICall>();
			ControlConstruct prevCC = null;
			ControlConstruct postCC = null;
			for (int i = 0; i < seq.size(); i++) {
				Item item = seq.get(i);
				if (item instanceof APICall) {
					String signature = ((APICall) item).name;
					if (signature.contains(focal + "(")) {
						// okay this is the focal API call
						theCall = (APICall) item;

						HashSet<String> relVars = new HashSet<String>();
						if (theCall.receiver != null) {
							relVars.add(theCall.receiver);
						}

						if (!theCall.arguments.isEmpty()) {
							relVars.addAll(theCall.arguments);
						}

						// scanning backward to find the first preceding call
						for (int j = i - 1; j >= 0; j--) {
							Item prev = seq.get(j);
							if (prev instanceof APICall) {
								APICall call = (APICall) prev;
								if (relVars.contains(call.receiver)
										|| relVars.contains(call.ret)) {
									prevCalls.add(0, call);
								}
							}
						}

						if (theCall.ret != null) {
							relVars.add(theCall.ret);
						}

						if (!theCall.arguments.isEmpty()) {
							// we don't care about those API calls that
							// manipulate the arguments of the focal API call
							relVars.removeAll(theCall.arguments);
						}

						// scanning forward to find the first succeeding call
						for (int j = i + 1; j < seq.size(); j++) {
							Item post = seq.get(j);
							if (post instanceof APICall) {
								APICall call = (APICall) post;

								boolean isRelevant = false;
								if (relVars.contains(call.receiver)) {
									isRelevant = true;
								} else {
									for (String arg : call.arguments) {
										if (relVars.contains(arg)) {
											isRelevant = true;
											break;
										}
									}
								}

								if (isRelevant) {
									postCalls.add(call);
								}
							}
						}

						// scanning backward to find the first preceding control
						// construct
						int closeBraceCount = 0;
						for (int j = i - 1; j >= 0; j--) {
							Item prev = seq.get(j);
							if (prev instanceof ControlConstruct) {
								String type = ((ControlConstruct) prev).type;
								if (type.equals("}")) {
									// there is likely to be a pre-check or
									// something
									closeBraceCount++;
								} else if (closeBraceCount == 0) {
									prevCC = (ControlConstruct) prev;
									if (prevCC.type.equals("IF {")
											|| prevCC.type.equals("LOOP {")) {
										// a dirty fix for mismatched control
										// structure and guard condition
										if (prevCC.type.equals("LOOP {")
												&& theCall.normalizedGuard
														.contains("!=null")) {
											continue;
										}

										// okay we need to fetch its predicate
										// from the enclosed calls
										prevCC.guard = theCall.normalizedGuard;
										prevCC.orgnGuard = theCall.originalGuard;
									}
									break;
								} else if (closeBraceCount > 0) {
									closeBraceCount--;
								}
							}
						}

						// scanning forward to find the first succeeding
						// construct
						for (int j = i + 1; j < seq.size(); j++) {
							Item post = seq.get(j);
							if (post instanceof ControlConstruct) {
								String type = ((ControlConstruct) post).type;

								if (type.equals("IF {")
										|| type.equals("LOOP {")) {
									postCC = (ControlConstruct) post;
									for (int k = j + 1; k < seq.size(); k++) {
										Item post2 = seq.get(k);
										if (post2 instanceof APICall) {
											String predicate = ((APICall) post2).originalGuard;
											// normalize the guard condition
											// with respect to the receiver,
											// return value, and the arguments
											// of the focal API
											String normalize = ProcessUtils
													.getNormalizedPredicate(
															predicate,
															theCall.receiver,
															theCall.arguments,
															theCall.ret);
											postCC.guard = normalize;
											postCC.orgnGuard = predicate;
											break;
										} else if (post2 instanceof ControlConstruct
												&& ((ControlConstruct) post2).type
														.equals("}")) {
											break;
										}
									}

									if (postCC.guard == null) {
										postCC.guard = "true";
										postCC.orgnGuard = "true";
									}
								} else {
									postCC = (ControlConstruct) post;
								}
								break;
							}
						}

						break;
					}
				}
			}

			if (theCall == null) {
				continue;
			}

			// download source code from GitHub if not cached already
			String[] ss = key.split("\\!");
			String projectURL = ss[0];
			String projectName = projectURL.substring(19);
			projectName = projectName.replaceAll("\\/", "-");
			String srcPath = ss[1];
			String className = ss[2];
			String methodName = ss[3];
			String url = projectURL + "/tree/master/" + srcPath;
			String dumpPath = path + File.separator + "dump" + File.separator
					+ projectName + File.separator + className + ".java";
			File dumpFile = new File(dumpPath);
			String method = "empty";
			String code = null;
			if (isFirstRun) {
				// fetch by url
				System.out.println("Fetching code from " + url);
				code = FetchSourceCode.fetchCodeByUrl(url);
				if (code != null) {
					if (!dumpFile.getParentFile().exists()) {
						dumpFile.getParentFile().mkdirs();
					}

					FileUtils.writeStringToFile(code, dumpPath);
				} else {
					numOfUnreachableUrls++;
				}
			} else {
				// fetch from cached file
				if (new File(dumpPath).exists()) {
					code = FileUtils.readFileToString(dumpPath);
				}
			}

			if (code == null) {
				// only printing those examples that we can fetch raw code
				continue;
			}

			// extract the corresponding method from the source code
			ASTParser p = getASTParser(code);
			CompilationUnit cu = (CompilationUnit) p.createAST(null);
			ArrayList<String> methods = new ArrayList<String>();
			final String src = code;
			cu.accept(new ASTVisitor() {
				@Override
				public boolean visit(MethodDeclaration node) {
					if (node.getName().toString().equals(methodName)) {
						int startLine = cu.getLineNumber(node
								.getStartPosition()) - 1;
						int endLine = cu.getLineNumber(node.getStartPosition()
								+ node.getLength()) - 1;
						String s = "";
						String[] ss = src.split(System.lineSeparator());
						for (int i = startLine; i <= endLine; i++) {
							s += ss[i] + System.lineSeparator();
						}
						methods.add(s);
					}

					return false;
				}
			});

			ArrayList<APICall> apiCalls = new ArrayList<APICall>();
			apiCalls.addAll(prevCalls);
			apiCalls.add(theCall);
			apiCalls.addAll(postCalls);

			ArrayList<ControlConstruct> controlConstructs = new ArrayList<ControlConstruct>();
			if (prevCC != null) {
				controlConstructs.add(prevCC);
			}
			if (postCC != null && !postCC.type.equals("}")) {
				controlConstructs.add(postCC);
			}

			MatchCodeElements matcher = new MatchCodeElements(methodName,
					theCall, apiCalls, controlConstructs);
			cu.accept(matcher);

			for (String m : methods) {
				if (m.contains(focal + "(")) {
					method = m;
					break;
				}
			}

			if (method.equals("empty")) {
				// only printing those code examples that we can find the
				// matching method
				continue;
			}

			// debug the location finder
			// int offset = code.indexOf(method);
			// for(APICall call : apiCalls) {
			// System.out.println(call);
			// if(matcher.callRanges.containsKey(call)) {
			// ArrayList<Point> ranges = matcher.callRanges.get(call);
			// for(Point range : ranges) {
			// System.out.println(method.substring(range.x - offset, range.y -
			// offset));
			// }
			// } else {
			// System.out.println("Not found");
			// }
			// }
			//
			// for(ControlConstruct cc : controlConstructs) {
			// System.out.println(cc);
			// if(matcher.controlRanges.containsKey(cc)) {
			// ArrayList<Pair<Point, Point>> ranges =
			// matcher.controlRanges.get(cc);
			// for(Pair<Point, Point> range : ranges) {
			// System.out.println(method.substring(range.getLeft().x - offset,
			// range.getLeft().y - offset));
			// }
			// } else {
			// System.out.println("Not found");
			// }
			// }

			// synthesize readable API calls and canonicalize variable names
			ArrayList<APICall> calls = new ArrayList<APICall>();
			calls.addAll(prevCalls);
			calls.add(theCall);
			calls.addAll(postCalls);
			String synthesizedCode;
			if (types.containsKey(key)) {
				synthesizedCode = synthesizeReableCode(calls, types.get(key));
			} else {
				synthesizedCode = synthesizeReableCode(calls,
						new HashMap<String, String>());
			}

			String[] arr = synthesizedCode.split(System.lineSeparator());

			int offset = code.indexOf(method);
			// find where the focal API call is
			int theCallStart = -1;
			int theCallEnd = -1;
			if (matcher.callRanges.containsKey(theCall)) {
				ArrayList<Point> range = matcher.callRanges.get(theCall);
				theCallStart = range.get(0).x;
				theCallEnd = range.get(0).y;
			} else {
				// TODO: we are screwed up
			}

			// construct the json string
			StringBuilder sb2 = new StringBuilder();
			sb2.append("{\"exampleID\": " + id + ", ");
			if (prevCC == null) {
				sb2.append("\"immediateControlStructure\": \"empty\", ");
				sb2.append("\"associatedPredicate\": \"empty\", ");
				sb2.append("\"immediateControlStructureExpressionStart\": -1, ");
				sb2.append("\"immediateControlStructureExpressionEnd\": -1, ");
				sb2.append("\"immediateControlStructureBlockStart\": -1, ");
				sb2.append("\"immediateControlStructureBlockEnd\": -1, ");
			} else {
				sb2.append("\"immediateControlStructure\": \"" + prevCC.type
						+ "\", ");
				sb2.append("\"associatedPredicate\": \""
						+ (prevCC.guard == null ? "empty" : StringEscapeUtils
								.escapeJava(prevCC.guard)) + "\", ");
				if (matcher.controlRanges.containsKey(prevCC)) {
					ArrayList<Pair<Point, Point>> ranges = matcher.controlRanges
							.get(prevCC);
					Pair<Point, Point> range = null;
					int delta = Integer.MAX_VALUE;
					for (Pair<Point, Point> pair : ranges) {
						int diff = theCallStart - pair.getLeft().x;
						if (diff < delta && diff >= 0) {
							range = pair;
							delta = diff;
						}
					}

					if (range == null) {
						// TODO: this is a bug---the control structure is
						// mismatched
						continue;
					}

					sb2.append("\"immediateControlStructureExpressionStart\": "
							+ (range.getLeft().x - offset) + ", ");
					sb2.append("\"immediateControlStructureExpressionEnd\": "
							+ (range.getLeft().y - offset) + ", ");
					sb2.append("\"immediateControlStructureBlockStart\": "
							+ (range.getRight().x - offset) + ", ");
					sb2.append("\"immediateControlStructureBlockEnd\": "
							+ (range.getRight().y - offset) + ", ");
				} else {
					int start = -1;
					int end = -1;
					if (prevCC.type.equals("TRY {")) {
						// it is likely that the exception is thrown from the
						// method declaration
						start = method.indexOf(" throws ");
						if (start != -1) {
							for (int i = start; i < method.length(); i++) {
								if (method.charAt(i) == '{') {
									end = i;
									break;
								}
							}
						}

					}
					sb2.append("\"immediateControlStructureExpressionStart\": "
							+ start + ", ");
					sb2.append("\"immediateControlStructureExpressionEnd\": "
							+ end + ", ");
					sb2.append("\"immediateControlStructureBlockStart\": "
							+ start + ", ");
					sb2.append("\"immediateControlStructureBlockEnd\": " + end
							+ ", ");
				}
			}

			sb2.append("\"focalAPI\": \"" + theCall.name + "\", ");
			sb2.append("\"synthesizedFocalAPI\": \""
					+ StringEscapeUtils.escapeJava(arr[prevCalls.size()])
					+ "\", ");
			sb2.append("\"focalAPIStart\": " + (theCallStart - offset) + ", ");
			sb2.append("\"focalAPIEnd\": " + (theCallEnd - offset) + ", ");

			if (postCC == null) {
				sb2.append("\"followUpControlStructure\": \"empty\", ");
				sb2.append("\"followUpAssociatedPredicate\": \"empty\", ");
				sb2.append("\"followUpControlStructureExpressionStart\": -1, ");
				sb2.append("\"followUpControlStructureExpressionEnd\": -1, ");
				sb2.append("\"followUpControlStructureBlockStart\": -1, ");
				sb2.append("\"followUpControlStructureBlockEnd\": -1, ");
			} else {
				sb2.append("\"followUpControlStructure\": \"" + postCC.type
						+ "\", ");
				sb2.append("\"followUpAssociatedPredicate\": \""
						+ (postCC.guard == null ? "empty" : StringEscapeUtils
								.escapeJava(postCC.guard)) + "\", ");
				if (matcher.controlRanges.containsKey(postCC)) {
					ArrayList<Pair<Point, Point>> ranges = matcher.controlRanges
							.get(postCC);
					Pair<Point, Point> range = null;
					int delta = Integer.MAX_VALUE;
					for (Pair<Point, Point> pair : ranges) {
						int diff = pair.getLeft().x - theCallStart;
						if (diff < delta && diff >= 0) {
							range = pair;
							delta = diff;
						}
					}

					if (range == null) {
						// TODO: this is a bug---the control structure is
						// mismatched
						continue;
					}

					sb2.append("\"followUpControlStructureExpressionStart\": "
							+ (range.getLeft().x - offset) + ", ");
					sb2.append("\"followUpControlStructureExpressionEnd\": "
							+ (range.getLeft().y - offset) + ", ");
					sb2.append("\"followUpControlStructureBlockStart\": "
							+ (range.getRight().x - offset) + ", ");
					sb2.append("\"followUpControlStructureBlockEnd\": "
							+ (range.getRight().y - offset) + ", ");
				} else {
					int start = -1;
					int end = -1;
					if (postCC.type.equals("CATCH")) {
						// it is likely that the exception is thrown from the
						// method declaration
						start = method.indexOf("throws ");
						if (start != -1) {
							for (int i = start; i < method.length(); i++) {
								if (method.charAt(i) == '{') {
									end = i;
									break;
								}
							}
						}
					}
					sb2.append("\"followUpControlStructureExpressionStart\": "
							+ start + ", ");
					sb2.append("\"followUpControlStructureExpressionEnd\": "
							+ end + ", ");
					sb2.append("\"followUpControlStructureBlockStart\": "
							+ start + ", ");
					sb2.append("\"followUpControlStructureBlockEnd\": " + end
							+ ", ");
				}
			}

			sb2.append("\"precedingAPICall\":[");
			if (!prevCalls.isEmpty()) {
				String s = "";
				for (APICall call : prevCalls) {
					s += "\"" + call.name + "\", ";
				}
				sb2.append(s.substring(0, s.length() - 2));
			}

			sb2.append("], ");

			sb2.append("\"synthesizedPrecedingAPICall\":[");
			if (!prevCalls.isEmpty()) {
				String s = "";
				for (int i = 0; i < prevCalls.size(); i++) {
					s += "\"" + StringEscapeUtils.escapeJava(arr[i]) + "\", ";
				}
				sb2.append(s.substring(0, s.length() - 2));
			}

			sb2.append("], ");

			sb2.append("\"precedingAPICallStart\":[");
			if (!prevCalls.isEmpty()) {
				String s = "";
				int next = theCallStart == -1 ? Integer.MAX_VALUE
						: theCallStart;
				ArrayList<Integer> starts = new ArrayList<Integer>();
				for (int i = prevCalls.size() - 1; i >= 0; i--) {
					APICall prevCall = prevCalls.get(i);
					if (matcher.callRanges.containsKey(prevCall)) {
						ArrayList<Point> ranges = matcher.callRanges
								.get(prevCall);
						Point range = null;
						for (int j = ranges.size() - 1; j >= 0; j--) {
							Point point = ranges.get(j);
							if (point.x < next) {
								range = point;
								break;
							}
						}
						if (range != null) {
							starts.add(0, range.x - offset);
							next = range.x;
						} else {
							starts.add(0, -1);
						}
					} else {
						starts.add(0, -1);
					}
				}

				for (int i = 0; i < prevCalls.size(); i++) {
					s += starts.get(i) + ", ";
				}

				sb2.append(s.substring(0, s.length() - 2));
			}

			sb2.append("], ");

			sb2.append("\"precedingAPICallEnd\":[");
			if (!prevCalls.isEmpty()) {
				String s = "";
				int next = theCallStart == -1 ? Integer.MAX_VALUE
						: theCallStart;
				ArrayList<Integer> ends = new ArrayList<Integer>();
				for (int i = prevCalls.size() - 1; i >= 0; i--) {
					APICall prevCall = prevCalls.get(i);
					if (matcher.callRanges.containsKey(prevCall)) {
						ArrayList<Point> ranges = matcher.callRanges
								.get(prevCall);
						Point range = null;
						for (int j = ranges.size() - 1; j >= 0; j--) {
							Point point = ranges.get(j);
							if (point.x < next) {
								range = point;
								break;
							}
						}
						if (range != null) {
							ends.add(0, range.y - offset);
							next = range.x;
						} else {
							ends.add(0, -1);
						}
					} else {
						ends.add(0, -1);
					}
				}

				for (int i = 0; i < prevCalls.size(); i++) {
					s += ends.get(i) + ", ";
				}

				sb2.append(s.substring(0, s.length() - 2));
			}

			sb2.append("], ");

			sb2.append("\"postAPICall\": [");
			if (!postCalls.isEmpty()) {
				String s = "";
				for (APICall call : postCalls) {
					s += "\"" + call.name + "\", ";
				}
				sb2.append(s.substring(0, s.length() - 2));
			}

			sb2.append("], ");

			sb2.append("\"synthesizedPostAPICall\":[");
			if (!postCalls.isEmpty()) {
				String s = "";
				for (int i = prevCalls.size() + 1; i < arr.length; i++) {
					s += "\"" + StringEscapeUtils.escapeJava(arr[i]) + "\", ";
				}
				sb2.append(s.substring(0, s.length() - 2));
			}

			sb2.append("], ");

			sb2.append("\"postAPICallStart\":[");
			if (!postCalls.isEmpty()) {
				String s = "";
				int prev = theCallEnd;
				for (int i = 0; i < postCalls.size(); i++) {
					APICall postCall = postCalls.get(i);
					if (matcher.callRanges.containsKey(postCall)) {
						ArrayList<Point> ranges = matcher.callRanges
								.get(postCall);
						Point range = null;
						for (int j = 0; j < ranges.size(); j++) {
							Point point = ranges.get(j);
							if (point.y > prev) {
								range = point;
								break;
							}
						}
						if (range != null) {
							s += range.x - offset;
							prev = range.y;
						} else {
							s += "-1";
						}
					} else {
						s += "-1";
					}

					s += ", ";
				}
				sb2.append(s.substring(0, s.length() - 2));
			}

			sb2.append("], ");

			sb2.append("\"postAPICallEnd\":[");
			if (!postCalls.isEmpty()) {
				String s = "";
				int prev = theCallEnd;
				for (int i = 0; i < postCalls.size(); i++) {
					APICall postCall = postCalls.get(i);
					if (matcher.callRanges.containsKey(postCall)) {
						ArrayList<Point> ranges = matcher.callRanges
								.get(postCall);
						Point range = null;
						for (int j = 0; j < ranges.size(); j++) {
							Point point = ranges.get(j);
							if (point.y > prev) {
								range = point;
								break;
							}
						}
						if (range != null) {
							s += range.y - offset;
							prev = range.y;
						} else {
							s += "-1";
						}
					} else {
						s += "-1";
					}

					s += ", ";
				}
				sb2.append(s.substring(0, s.length() - 2));
			}

			sb2.append("], ");

			sb2.append("\"url\": \"" + url + "\", ");

			sb2.append("\"synthesizedCode\": \""
					+ StringEscapeUtils.escapeJava(synthesizedCode) + "\", ");
			sb2.append("\"rawCode\": \"" + StringEscapeUtils.escapeJava(method)
					+ "\"}");

			FileUtils.appendStringToFile(
					sb2.toString() + "," + System.lineSeparator(), output);

			// String logFile =
			// "/media/troy/Disk2/Boa/apis/Map.get/synthesis.txt";
			// // print raw code
			// FileUtils.appendStringToFile("Raw code: \n", logFile);
			// FileUtils.appendStringToFile(method, logFile);
			// FileUtils.appendStringToFile("Simplified code: \n", logFile);
			// ArrayList<APICall> calls = new ArrayList<APICall>();
			// calls.addAll(prevCalls);
			// calls.add(theCall);
			// calls.addAll(postCalls);
			// if(types.containsKey(key)) {
			// FileUtils.appendStringToFile(synthesizeReableCode(calls,
			// types.get(key)) + "\n", logFile);
			// } else {
			// FileUtils.appendStringToFile(synthesizeReableCode(calls, new
			// HashMap<String, String>()) + "\n", logFile);
			// }

			id++;
		}

		FileUtils.appendStringToFile("]", output);
		// log the number of unreachable urls
		System.out.println(numOfUnreachableUrls);
	}

	private ASTParser getASTParser(String sourceCode) {
		ASTParser parser = ASTParser.newParser(AST.JLS8);
		parser.setStatementsRecovery(true);
		parser.setKind(ASTParser.K_COMPILATION_UNIT);
		parser.setSource(sourceCode.toCharArray());
		parser.setResolveBindings(true);
		parser.setBindingsRecovery(true);
		Map options = JavaCore.getOptions();
		JavaCore.setComplianceOptions(JavaCore.VERSION_1_5, options);
		parser.setCompilerOptions(options);
		return parser;
	}

	public static void main(String[] args) {
		//query(String table, String[] columns, String selection, String[] selectionArgs, String groupBy, String having, String orderBy)
		ArrayList<ArrayList<String>> argNameList = new ArrayList<ArrayList<String>>();
		ArrayList<String> argNames1 = new ArrayList<String>();
		argNames1.add("file");
		ArrayList<String> argNames2 = new ArrayList<String>();
		argNames2.add("fileDescripter");
		ArrayList<String> argNames3 = new ArrayList<String>();
		argNames3.add("fileName");
		argNameList.add(argNames1);
		argNameList.add(argNames2);
		argNameList.add(argNames3);
		Preprocess.argNameList = argNameList;
		ArrayList<ArrayList<String>> argTypeList = new ArrayList<ArrayList<String>>();
		ArrayList<String> argTypes1 = new ArrayList<String>();
		argTypes1.add("File");
		ArrayList<String> argTypes2 = new ArrayList<String>();
		argTypes2.add("FileDescriptor");
		ArrayList<String> argTypes3 = new ArrayList<String>();
		argTypes3.add("String");
		argTypeList.add(argTypes1);
		argTypeList.add(argTypes2);
		argTypeList.add(argTypes3);
		Preprocess.argTypeList = argTypeList;
		Preprocess.rcvName = null;
		Preprocess.rcvType = null;
		Preprocess.retName = "stream";
		Preprocess.retType = "FileInputStream";

		String focal = "new FileInputStream";
		String input = "/media/troy/Disk2/Boa/apis/FileInputStream.FileInputStream";
		Preprocess pp = new Preprocess(input, focal);
		pp.process();

		String output = "/media/troy/Disk2/Boa/apis/FileInputStream.FileInputStream/evis.txt";
		pp.dumpToJsonNewSchema(output);
	}
}
