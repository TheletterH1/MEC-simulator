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
		long taskDataEntrySize = (long) (36.288 * 8 * Math.pow(10, 6));
		long taskResultSize = (long) Math.pow(10, 4);
		long computacionalLoadCPUcycles = (long) (20 * Math.pow(10, 6));
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
		List<Integer> listNumberOfTasks = Arrays.asList(500);
		List<Integer> listNumberIoTDevices = Arrays.asList(100, 500, 1000);
		List<Integer> listNumberMECServers = Arrays.asList(1, 2);

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
						Random rand = new Random();
						double dependencyProbability = 0.3; // 30% chance of inter-task dependency
						Map<Integer, List<String>> dependencyMap = Application.generateDependencies(
								numberTasks, dependencyProbability, rand);
						List<Task> waitingTasksQueue = new ArrayList<Task>(); // Tasks waiting on dependencies
						Set<String> finishedTaskIds = new HashSet<String>(); // IDs of completed tasks
						printMessageOnConsole(
								"IUTD: Generated dependency DAG with probability " + dependencyProbability);

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
										// Variable task cost logic: vary base parameters by +/- 50%
										double loadFactor = 0.5 + rand.nextDouble(); // Random factor [0.5, 1.5]
										long varLoad = (long) (app.getComputationalLoad() * loadFactor);
										long varData = (long) (app.getDataEntrySize() * loadFactor);

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
											alpha, beta, gamma, listRunningTasks, systemTime, i);

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

										finishedTaskIds.add(task.getIdTask());

										// ---------------------------------------------------------------------------
										// 6.1. Free resources
										// ---------------------------------------------------------------------------
										if (task.getPolicy() == POLICY1_IOT) {
											int id = Integer.parseInt(task.getIdDeviceGenerator().split("-")[1]);
											listOfIoTDevices[id].alterCPUStatus(CORE_FREE);
											task.setExecutionSite("IoT-" + id);
										}
										if (task.getPolicy() == POLICY2_MEC) {
											listOfMECServers[task.getAssignedMECServerId()].freeCPU();
											task.setExecutionSite("MEC-" + task.getAssignedMECServerId());
										}
										if (task.getPolicy() == POLICY3_CLOUD) {
											task.setExecutionSite("Cloud");
										}

										if (Boolean.TRUE) {
											if (numberTasksCanceledAndConcluded % 100 == 0)
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
												scheduleAndAllocateTask(waitingTask, listOfIoTDevices,
														listOfMECServers, numberIoTDevices, numberMECServers,
														coefficientEnergy, coefficientTime, alpha, beta, gamma,
														listRunningTasks, systemTime, devIdx);
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

	public static void scheduleAndAllocateTask(Task task, IoTDevice[] listOfIoTDevices,
			MECServer[] listOfMECServers, int numberIoTDevices, int numberMECServers,
			double coefficientEnergy, double coefficientTime,
			double alpha, double beta, double gamma,
			List<Task> listRunningTasks, long systemTime, int deviceIndex) {

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

		// Ennead<Time; Task-ID; Policy; Finalization Status, Energy CPU core, Energy
		// data
		// transmission, Time CPU core, Time data transmission, Cost>
		List<Ennead<Long, String, String, String, Long, Long, Long, Long, Long>> listEnnead = new ArrayList<Ennead<Long, String, String, String, Long, Long, Long, Long, Long>>();

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

			Ennead<Long, String, String, String, Long, Long, Long, Long, Long> ennead = new Ennead<Long, String, String, String, Long, Long, Long, Long, Long>(
					(long) (tasksFinalized[i].getBaseTime() + tasksFinalized[i].getTotalElapsedTime()),
					tasksFinalized[i].getIdTask(),
					policy,
					statusFinalizacao,
					(long) tasksFinalized[i].getExecutionEnergy(),
					(long) tasksFinalized[i].getTransferEnergy(),
					tasksFinalized[i].getExecutionTime(),
					tasksFinalized[i].getTransferTime(),
					(long) (coefficientEnergy * tasksFinalized[i].getTotalConsumedEnergy()
							+ coefficientTime * tasksFinalized[i].getTotalElapsedTime()));

			listEnnead.add(ennead);
		}

		// Order tuple list by the tasks finalization time
		listEnnead.sort(null);

		String header = "Time;Task-ID;Policy;Finalization Status;CPU core Energy;Transfer Energy;CPU core Time;Transfer Time;Cost\n";
		printEnneadsToFile(filename, header, listEnnead);
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

		writer.write(header);
		for (Ennead<Long, String, String, String, Long, Long, Long, Long, Long> ennead : listEnnead) {
			writer.write(ennead.getValue0() + ";" + ennead.getValue1() + ";" + ennead.getValue2() + ";" +
					ennead.getValue3() + ";" + ennead.getValue4() + ";" + ennead.getValue5() + ";" +
					ennead.getValue6() + ";" + ennead.getValue7() + ";" + ennead.getValue8() + "\n");
		}
		writer.close();
	}
}
