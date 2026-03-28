package main;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.javatuples.Octet;
import org.javatuples.Ennead;

/* Author: Jo�o Luiz Grave Gross (@jlggross)
 * Github: https://github.com/jlggross/MEC-simulator
 * Dependencies: javatuples-1.2.jar
 *
 * General info:
 * - The systems cycle time is 1 micro second.
 * - The simulation checks the entire system and advances 1 micro second in time.  
 * - All decision making process is made in intervals of 1 micro second.
 * - The simulation ends when all tasks are finalized. 
 * 
 * Times:
 * - All times used in the variables are in micro seconds (the base time unit for the simulation)
 * */

public class SimulatorExecution {

	public static java.util.Map<String, Integer> gibbsPolicies = new java.util.HashMap<>();
	public static double gibbsBestObj = 0.0;

	static int CORE_FREE = 1;
	static int CORE_OCCUPIED = 2;

	static int POLICY1_IOT = 1;
	static int POLICY2_MEC = 2;
	static int POLICY3_CLOUD = 3;

	static int TASK_ALIVE = 1;
	static int TASK_CONCLUDED = 2;
	static int TASK_CANCELED = 3;

	public static void main(String args[]) throws IOException {

		// ---------------------------------------------------------------------------
		// 1. Setting simulation variables
		// * Application
		// * Number of tasks
		// * Number of IoT Devices
		// * Number of MEC Servers
		// * Energy and time coefficients for task cost calculation
		// * Alpha, beta and gamma coefficients for task cost calculation
		// ---------------------------------------------------------------------------

		// Applications used in the simulation
		List<Application> appList = new ArrayList<Application>();

		// Application 1
		long taskGenerationRate = (long) (10 * Math.pow(10, 4));
		long taskDataEntrySize = (long) (2.2 * 8 * Math.pow(10, 6));
		long taskResultSize = (long) Math.pow(10, 4);
		long computacionalLoadCPUcycles = (long) (200 * Math.pow(10, 6));
		long deadlineCriticalTasks = (long) (0.5 * Math.pow(10, 6));
		double percentageOfCriticalTasks = (double) 0.1;
		appList.add(new Application("App1", taskGenerationRate, taskDataEntrySize, taskResultSize,
				computacionalLoadCPUcycles, percentageOfCriticalTasks, deadlineCriticalTasks));

		// Application 2
		taskGenerationRate = (long) (0.1 * Math.pow(10, 6));
		taskDataEntrySize = (long) (4 * 8 * Math.pow(10, 6));
		taskResultSize = (long) (5 * Math.pow(10, 3));
		computacionalLoadCPUcycles = (long) (200 * Math.pow(10, 6));
		deadlineCriticalTasks = (long) (0.1 * Math.pow(10, 6));
		percentageOfCriticalTasks = (double) 0.5;
		// appList.add(new Application("App2", taskGenerationRate, taskDataEntrySize,
		// taskResultSize, computacionalLoadCPUcycles, percentageOfCriticalTasks,
		// deadlineCriticalTasks));

		// Lists for number of tasks, IoT devices and MEC servers
		List<Integer> listNumberOfTasks = Arrays.asList(50);
		List<Integer> listNumberIoTDevices = Arrays.asList(2);
		List<Integer> listNumberMECServers = Arrays.asList(1);

		for (int numberTasks : listNumberOfTasks) {
			for (int numberIoTDevices : listNumberIoTDevices) {
				if (numberIoTDevices > numberTasks)
					continue;
				for (int numberMECServers : listNumberMECServers) {
					List<Task> listRunningTasks = new ArrayList<Task>();
					Task[] listFinishedTasks = new Task[numberTasks];
					for (Application app : appList) {
						app.setNumberOfTasks(numberTasks); // Define number of tasks that will be created

						// Set coefficients to calculate the tasks cost in each allocation option
						double coefficientEnergy, coefficientTime;
						coefficientEnergy = 4.0 / 5.0;
						coefficientTime = 1 - coefficientEnergy;

						// Set the coefficients used in the tasks minimization equation
						double alpha, beta, gamma;
						alpha = 0.33;
						beta = 0.33;
						gamma = 0.33;

						// ---------------------------------------------------------------------------
						// 2. Create entities for the architecture
						// * Creates the IoT Devices
						// * Creates the MEC Servers
						// * A DataCenter entity is created my the Scheduler to access the Cloud
						// characteristics. Cloud is meant to have virtual infinite resources.
						// ---------------------------------------------------------------------------
						long rateOfGeneratedTasks = app.getRateGeneration();

						IoTDevice[] listOfIoTDevices = new IoTDevice[numberIoTDevices];
						for (int i = 0; i < numberIoTDevices; i++)
							listOfIoTDevices[i] = new IoTDevice("Device-" + i, rateOfGeneratedTasks);

						MECServer[] listOfMECServers = new MECServer[numberMECServers];
						for (int i = 0; i < numberMECServers; i++)
							listOfMECServers[i] = new MECServer("MEC-" + i);

						// ---------------------------------------------------------------------------
						// 3. Initializes simulation control variables
						// ---------------------------------------------------------------------------
						long systemTime = 0; // Initializes simulation time (starts in zero)
						int numberTasksCanceledAndConcluded = 0;
						int numberCreatedTasks = 0;
						printMessageOnConsole("LoadVariationExperiment " + numberTasks + "-" + numberIoTDevices + "-"
								+ numberMECServers + "-" + (long) app.getComputationalLoad());

						// ---------------------------------------------------------------------------
						// 3.1. Generate dependency map and initialize structures
						// ---------------------------------------------------------------------------
						Random rand = new Random(42);
						// double dependencyProbability = 0.3; // 30% chance of inter-task dependency
						Map<Integer, List<String>> dependencyMap = new java.util.HashMap<>();

						List<Task> waitingTasksQueue = new ArrayList<Task>(); // Tasks waiting on dependencies
						Set<String> finishedTaskIds = new HashSet<String>(); // IDs of completed tasks

						// --- GIBBS SAMPLING PRE-GENERATION ---
						gibbsPolicies.clear(); // Reset for new loop
						long[] randLoadList = new long[numberTasks];
						long[] randDataList = new long[numberTasks];
						long minLoad = (long) (10 * Math.pow(10, 6)); // 10 Mcycles
						long maxLoad = (long) (200 * Math.pow(10, 6)); // 200 Mcycles
						for (int i = 0; i < numberTasks; i++) {
							randLoadList[i] = minLoad + (long) (rand.nextDouble() * (maxLoad - minLoad));
							randDataList[i] = (long) (1 * 8 * Math.pow(10, 6)); // Fix data to 1MB
						}

						if (numberIoTDevices == 2) {
							// --- Simulate task creation order to determine device assignment ---
							// Device baseTime is random, so we can't assume even=Device-0.
							// Instead, simulate the timing loop to find out which device creates each task.
							int[] taskToDevice = new int[numberTasks];
							int simCreated = 0;
							long simTime = 0;
							while (simCreated < numberTasks) {
								for (int i = 0; i < numberIoTDevices && simCreated < numberTasks; i++) {
									if (((simTime - listOfIoTDevices[i].getBaseTime()) % app.getRateGeneration()) == 0
											&& simTime >= listOfIoTDevices[i].getBaseTime()) {
										taskToDevice[simCreated] = i;
										simCreated++;
									}
								}
								simTime++;
							}

							// Count tasks per device
							List<Integer> dev0Tasks = new ArrayList<>(); // WD1
							List<Integer> dev1Tasks = new ArrayList<>(); // WD2
							for (int i = 0; i < numberTasks; i++) {
								if (taskToDevice[i] == 0)
									dev0Tasks.add(i);
								else
									dev1Tasks.add(i);
							}
							int M = dev0Tasks.size(); // WD1 task count
							int N = dev1Tasks.size(); // WD2 task count
							int k = N / 2;

							// --- Build intra-device sequential dependencies ---
							// Each task depends on the previous task from the SAME device.
							int[] lastTaskOnDevice = new int[numberIoTDevices];
							Arrays.fill(lastTaskOnDevice, -1);
							for (int i = 0; i < numberTasks; i++) {
								int devId = taskToDevice[i];
								if (lastTaskOnDevice[devId] >= 0) {
									List<String> seqDeps = dependencyMap.getOrDefault(i, new ArrayList<>());
									seqDeps.add("Task-" + lastTaskOnDevice[devId]);
									dependencyMap.put(i, seqDeps);
								}
								lastTaskOnDevice[devId] = i;
							}

							// --- Add cross-device Gibbs dependency ---
							// WD1's last task -> WD2's k-th task
							int wd1LastGlobalId = dev0Tasks.get(M - 1);
							int wd2KthGlobalId = dev1Tasks.get(k);
							List<String> crossDeps = dependencyMap.getOrDefault(wd2KthGlobalId, new ArrayList<>());
							crossDeps.add("Task-" + wd1LastGlobalId);
							dependencyMap.put(wd2KthGlobalId, crossDeps);

							printMessageOnConsole("IUTD: WD1(Device-0) has " + M + " tasks, WD2(Device-1) has " + N
									+ " tasks, k=" + k + ", cross-dep: Task-" + wd1LastGlobalId + " -> Task-"
									+ wd2KthGlobalId);

							// --- Build task lists for Gibbs optimization ---
							List<Task> wd1Tasks = new ArrayList<>();
							List<Task> wd2Tasks = new ArrayList<>();
							for (int i = 0; i < numberTasks; i++) {
								int devId = taskToDevice[i];
								Task t = new Task("Task-" + i, listOfIoTDevices[devId].getId(), -1, 0, randLoadList[i],
										randDataList[i], app.getResultsSize());
								if (devId == 0)
									wd1Tasks.add(t);
								else
									wd2Tasks.add(t);
							}
							GibbsScheduler.MECSystemParams sysParam = new GibbsScheduler.MECSystemParams();
							// Paper Fig 8/9 setup: d1=15m, d2=10m.
							double d1 = 15.0;
							double[] h1 = new double[wd1Tasks.size() + 1];
							Arrays.fill(h1, GibbsScheduler.freeSpaceChannel(d1, 4.11, 915e6, 3.0));

							double d2 = 10.0;
							double[] h2 = new double[wd2Tasks.size() + 1];
							Arrays.fill(h2, GibbsScheduler.freeSpaceChannel(d2, 4.11, 915e6, 3.0));

							GibbsScheduler.WirelessDeviceWrapper wd1Wrap = new GibbsScheduler.WirelessDeviceWrapper(
									listOfIoTDevices[0], wd1Tasks, h1, h1);
							wd1Wrap.beta_T = 0.05; // Fig 8 settings
							wd1Wrap.beta_E = 0.95;

							GibbsScheduler.WirelessDeviceWrapper wd2Wrap = new GibbsScheduler.WirelessDeviceWrapper(
									listOfIoTDevices[1], wd2Tasks, h2, h2);
							wd2Wrap.beta_T = 0.50;
							wd2Wrap.beta_E = 0.50;

							GibbsScheduler.GibbsResult gRes = GibbsScheduler.gibbsSampling(wd1Wrap, wd2Wrap, k,
									sysParam, 1.0, 0.95, 500, 1e-6, 42);
							gibbsBestObj = gRes.bestObj;
							for (int j = 0; j < M; j++)
								gibbsPolicies.put(wd1Tasks.get(j).getIdTask(),
										gRes.a1[j] == 0 ? POLICY1_IOT : POLICY2_MEC);
							for (int j = 0; j < N; j++)
								gibbsPolicies.put(wd2Tasks.get(j).getIdTask(),
										gRes.a2[j] == 0 ? POLICY1_IOT : POLICY2_MEC);
							printMessageOnConsole(
									"IUTD: Sequential + Gibbs dependencies set. Best OBJ: " + gRes.bestObj);
							printMessageOnConsole("IUTD: a1 (WD1) = " + Arrays.toString(gRes.a1));
							printMessageOnConsole("IUTD: a2 (WD2) = " + Arrays.toString(gRes.a2));
						}
						// ---------------------------------------------------------------------------
						// 4. Initiates simulation
						// ---------------------------------------------------------------------------
						while (Boolean.TRUE) {

							// ---------------------------------------------------------------------------
							// 5. Verify if there are tasks to be created
							// ---------------------------------------------------------------------------
							for (int i = 0; i < numberIoTDevices; i++) {
								if (((systemTime - listOfIoTDevices[i].getBaseTime()) % app.getRateGeneration()) == 0) {

									// ---------------------------------------------------------------------------
									// 5.1. A task is created
									// ---------------------------------------------------------------------------
									Task newTask = new Task("TarefaDummy", "DeviceDummy", -1, 0, 0, 0, 0);
									if (numberCreatedTasks < numberTasks) {
										// Task property fetched from pre-gen arrays to ensure consistency
										long varLoad = randLoadList[numberCreatedTasks];
										long varData = randDataList[numberCreatedTasks];

										if (app.defineIfTaskIsCritical(numberCreatedTasks) == Boolean.TRUE) {
											newTask = new Task("Task-" + numberCreatedTasks,
													listOfIoTDevices[i].getId(),
													app.getCriticalTasksDeadline(), systemTime,
													varLoad, varData, app.getResultsSize());
										} else {
											newTask = new Task("Task-" + numberCreatedTasks,
													listOfIoTDevices[i].getId(), -1,
													systemTime, varLoad, varData,
													app.getResultsSize());
										}

										// IUTD: Assign dependencies from the pre-generated DAG
										List<String> deps = dependencyMap.get(numberCreatedTasks);
										if (deps != null) {
											newTask.setDependencies(deps);
										}

										numberCreatedTasks++;
									} else
										break;

									// ---------------------------------------------------------------------------
									// 5.2. IUTD: Check if task dependencies are satisfied
									// ---------------------------------------------------------------------------
									boolean dependenciesMet = true;
									for (String depId : newTask.getDependencies()) {
										if (!finishedTaskIds.contains(depId)) {
											dependenciesMet = false;
											break;
										}
									}

									if (!dependenciesMet) {
										// Task has unmet dependencies — put in waiting queue
										waitingTasksQueue.add(newTask);
										continue; // Skip scheduling for this task
									}

									// ---------------------------------------------------------------------------
									// 5.3. Start the scheduler and task allocation
									// ---------------------------------------------------------------------------
									scheduleAndAllocateTask(newTask, listOfIoTDevices, listOfMECServers,
											numberIoTDevices, numberMECServers, coefficientEnergy, coefficientTime,
											alpha, beta, gamma, listRunningTasks, systemTime, i, 0);

									// ---------------------------------------------------------------------------
									// Iteration end - Go back to 5.
									// ---------------------------------------------------------------------------
								}
							}

							// ---------------------------------------------------------------------------
							// 6. Verify if tasks are finished
							// ---------------------------------------------------------------------------
							if (!listRunningTasks.isEmpty()) {
								List<Task> listRunningTasksAux = new ArrayList<Task>();
								listRunningTasksAux.addAll(listRunningTasks);
								for (Task aux : listRunningTasksAux) {
									Task task = aux;

									if (task.verifyIfTaskMustFinish(systemTime) == Boolean.TRUE) {
										listFinishedTasks[numberTasksCanceledAndConcluded] = task;
										numberTasksCanceledAndConcluded++;
										listRunningTasks.remove(aux);

										// IUTD: Record finished task ID for dependency tracking
										finishedTaskIds.add(task.getIdTask());

										// ---------------------------------------------------------------------------
										// 6.1. Free resources
										// ---------------------------------------------------------------------------
										if (task.getPolicy() == POLICY1_IOT) {
											int id = Integer.parseInt(task.getIdDeviceGenerator().split("-")[1]);
											listOfIoTDevices[id].alterCPUStatus(CORE_FREE);
											task.setExecutionSite("IoT-" + id); // IUTD: Record execution site
										}
										if (task.getPolicy() == POLICY2_MEC) {
											// Fixed: Free core on the SPECIFIC server that processed it
											listOfMECServers[task.getAssignedMECServerId()].freeCPU();
											task.setExecutionSite("MEC-" + task.getAssignedMECServerId()); // IUTD
										}
										if (task.getPolicy() == POLICY3_CLOUD) {
											task.setExecutionSite("Cloud"); // IUTD: Record execution site
										}

										if (Boolean.TRUE) {
											if (numberTasksCanceledAndConcluded % 100 == 0) // Restrict prints
												printMessageOnConsole("Number of tasks concluded: "
														+ numberTasksCanceledAndConcluded);
										}

										// ---------------------------------------------------------------------------
										// 6.2 IUTD: Check if any waiting tasks can now be scheduled
										// ---------------------------------------------------------------------------
										List<Task> waitingTasksAux = new ArrayList<Task>(waitingTasksQueue);
										for (Task waitingTask : waitingTasksAux) {
											boolean allDepsMet = true;
											for (String depId : waitingTask.getDependencies()) {
												if (!finishedTaskIds.contains(depId)) {
													allDepsMet = false;
													break;
												}
											}
											if (allDepsMet) {
												waitingTasksQueue.remove(waitingTask);
												// Find the device index from the task's device ID
												int devIdx = Integer.parseInt(
														waitingTask.getIdDeviceGenerator().split("-")[1]);

												// Update basetime to current system time so it finishes properly
												waitingTask.setBaseTime(systemTime);

												scheduleAndAllocateTask(waitingTask, listOfIoTDevices,
														listOfMECServers, numberIoTDevices, numberMECServers,
														coefficientEnergy, coefficientTime, alpha, beta, gamma,
														listRunningTasks, systemTime, devIdx, 0);
											}
										}
									}
								}
							}

							// ---------------------------------------------------------------------------
							// 7. Verify if all tasks are concluded or canceled
							// ---------------------------------------------------------------------------
							if (numberTasksCanceledAndConcluded == numberTasks) {
								if (Boolean.FALSE) {
									for (int j = 0; j < numberTasks; j++) {
										System.out.println(listFinishedTasks[j].getIdTask() + "; Energia: "
												+ listFinishedTasks[j].getTotalConsumedEnergy());
									}
								}
								break; // Finishes simulation round
							}

							// Updates system time - advances 1 micro second in time
							systemTime++;
						}

						// ---------------------------------------------------------------------------
						// Simulation round ended - Print results for analysis
						// ---------------------------------------------------------------------------
						if (Boolean.TRUE) {
							String filename = "01-" + numberTasks + "-" + numberIoTDevices + "-" + numberMECServers
									+ "-" + (long) app.getComputationalLoad();
							String testType = "LoadVariation";
							printSimulationLog(filename, listFinishedTasks, coefficientEnergy, coefficientTime,
									testType);
							printMessageOnConsole("Simulation results saved to: " + filename + "-" + testType + ".txt");
						}
					}
				}
			}
		}

	}

	/*
	 * Print message on console
	 * 
	 */
	public static void printMessageOnConsole(String message) {
		System.out.println(message);
	}

	/*
	 * Print simulation log for analysis
	 * - filename: the name to put in the output file
	 * - tasksFInalized: set of finalized tasks in the simulation
	 * - coefficientEnergy: Coefficient used for energy consumption in the task's
	 * cost equation
	 * - coefficientTime: Coefficient used for elapsed time in the task's cost
	 * equation
	 * - testType: name of the test/experiment being executed in the simulation
	 * 
	 * Observation: The content of the .txt file is separated with commas, so it can
	 * be easily
	 * imported in excel with the the import from text option using a delimiter
	 * (comma).
	 * 
	 */
	public static void printSimulationLog(String filename, Task[] tasksFinalized,
			double coefficientEnergy, double coefficientTime, String testType) throws IOException {

		filename = filename + "-" + testType + ".txt";

		BufferedWriter writer = new BufferedWriter(new FileWriter(filename));
		writer.write("# Total ETC (Gibbs Best OBJ): " + gibbsBestObj + "\n");

		String header = "Time;Task-ID;Device;Policy;Finalization Status;CPU core Energy;Transfer Energy;CPU core Time;Transfer Time;Cost\n";
		writer.write(header);

		for (int i = 0; i < tasksFinalized.length; i++) {
			String policy;
			if (tasksFinalized[i].getPolicy() == POLICY1_IOT)
				policy = "POLICY1_IOT";
			else if (tasksFinalized[i].getPolicy() == POLICY2_MEC)
				policy = "POLICY2_MEC";
			else
				policy = "POLICY3_CLOUD";

			String statusFinalizacao;
			if (tasksFinalized[i].getTaskStatus() == TASK_CONCLUDED)
				statusFinalizacao = "TASK_CONCLUDED";
			else
				statusFinalizacao = "TASK_CANCELED";

			long time = (long) (tasksFinalized[i].getBaseTime() + tasksFinalized[i].getTotalElapsedTime());
			long cost = (long) (coefficientEnergy * tasksFinalized[i].getTotalConsumedEnergy()
					+ coefficientTime * tasksFinalized[i].getTotalElapsedTime());

			writer.write(time + ";" + tasksFinalized[i].getIdTask() + ";"
					+ tasksFinalized[i].getIdDeviceGenerator() + ";" + policy + ";"
					+ statusFinalizacao + ";"
					+ (long) tasksFinalized[i].getExecutionEnergy() + ";"
					+ (long) tasksFinalized[i].getTransferEnergy() + ";"
					+ tasksFinalized[i].getExecutionTime() + ";"
					+ tasksFinalized[i].getTransferTime() + ";"
					+ cost + "\n");
		}
		writer.close();
	}

	/*
	 * Print Octet
	 * [Time; Policy; Finalization Status, Energy CPU core, Energy data
	 * transmission, Time CPU core, Time data transmission, Cost]
	 * 
	 * 0 Time : Time in which the task was ended in the system.
	 * 1 Policy : Chosen allocation option/policy. Can be 1 (IoT, 2 (MEC) or 3
	 * (Cloud).
	 * 2 Finalization Status : Task finalization status. Can be concluded or
	 * canceled.
	 * 3 Energy CPU core : Dynamic energy consumed by the CPU core during execution.
	 * 4 Energy data transmission : Consumed energy for data transmissions
	 * 5 Time CPU core : Elapsed time when executing the task in the CPU core
	 * 6 Time data transmission : Elapsed time for data transmissions
	 * 7 Cost : Cost
	 * 
	 */
	public static void printEnneadsToFile(String filename, String header,
			List<Ennead<Long, String, String, String, Long, Long, Long, Long, Long>> listEnnead) throws IOException {

		BufferedWriter writer = new BufferedWriter(new FileWriter(filename));

		writer.write("# Total ETC (Gibbs Best OBJ): " + gibbsBestObj + "\n");
		writer.write(header);
		for (Ennead<Long, String, String, String, Long, Long, Long, Long, Long> ennead : listEnnead) {
			writer.write(ennead.getValue0() + ";" + ennead.getValue1() + ";" + ennead.getValue2() + ";" +
					ennead.getValue3() + ";" + ennead.getValue4() + ";" + ennead.getValue5() + ";" +
					ennead.getValue6() + ";" + ennead.getValue7() + ";" + ennead.getValue8() + "\n");
		}
		writer.close();
	}

	public static void scheduleAndAllocateTask(Task task, IoTDevice[] listIoTDevices,
			MECServer[] listMECServers, int numberIoTDevices, int numberMECServers, double coefficientEnergy,
			double coefficientTime,
			double alpha, double beta, double gamma, List<Task> listRunningTasks, long systemTime, int devIdx,
			int specialCase) {

		if (specialCase == 1) {
			// Force all to IoT
			task.setPolicy(POLICY1_IOT);
		} else if (specialCase == 2) {
			// Force all to MEC
			task.setPolicy(POLICY2_MEC);
		}
		int policyToApply;
		if (gibbsPolicies.containsKey(task.getIdTask())) {
			policyToApply = gibbsPolicies.get(task.getIdTask());
		} else {
			boolean flagIoTDevice = listIoTDevices[devIdx].verifyCPUFree() == Boolean.TRUE;
			boolean flagMECServer = false;
			for (MECServer mec : listMECServers) {
				if (mec.verifyCPUFree() == Boolean.TRUE) {
					flagMECServer = true;
					break;
				}
			}
			Scheduler scheduler = new Scheduler(task, coefficientEnergy, coefficientTime, alpha, beta, gamma);
			Octet<Double, Double, Double, Double, Double, Long, Double, Integer> allocation = scheduler
					.defineAllocationPolicy(flagIoTDevice, flagMECServer);
			policyToApply = allocation.getValue7();
		}

		task.setPolicy(policyToApply);

		if (policyToApply == POLICY1_IOT) {
			listIoTDevices[devIdx].alterCPUStatus(CORE_OCCUPIED);
			long freq = listIoTDevices[devIdx].getPairsFrequencyVoltage().get(0).getValue0();
			double volt = listIoTDevices[devIdx].getPairsFrequencyVoltage().get(0).getValue1();
			task.setExecutionTime(listIoTDevices[devIdx].calculateExecutionTime(freq, task.getComputationalLoad()));
			task.setExecutionEnergy(
					listIoTDevices[devIdx].calculateDynamicEnergyConsumed(freq, volt, task.getComputationalLoad()));
			task.setTransferTime(0);
			task.setTransferEnergy(0);
		} else if (policyToApply == POLICY2_MEC) {
			int targetMec = 0;
			for (int j = 0; j < numberMECServers; j++) {
				if (listMECServers[j].verifyCPUFree() == Boolean.TRUE) {
					targetMec = j;
					break;
				}
			}
			task.setAssignedMECServerId(targetMec);
			listMECServers[targetMec].ocuppyCPU();

			long freq = listMECServers[targetMec].getPairsFrenquecyVoltage().get(0).getValue0();
			double volt = listMECServers[targetMec].getPairsFrenquecyVoltage().get(0).getValue1();
			task.setExecutionTime(listMECServers[targetMec].calculateExecutionTime(freq, task.getComputationalLoad()));
			task.setExecutionEnergy(
					listMECServers[targetMec].calculateDynamicEnergyConsumed(freq, volt, task.getComputationalLoad()));

			RAN_5G ran = new RAN_5G();
			task.setTransferTime(ran.calculateTransferTime(task.getEntryDataSize())
					+ ran.calculateTransferTime(task.getReturnDataSize()));
			task.setTransferEnergy(ran.calculateConsumedEnergy(task.getEntryDataSize())
					+ ran.calculateConsumedEnergy(task.getReturnDataSize()));
		}
		listRunningTasks.add(task);
	}
}
