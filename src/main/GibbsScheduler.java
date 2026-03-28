package main;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class GibbsScheduler {

    public static class MECSystemParams {
        public double W = 2e6; // 2 MHz bandwidth
        public double sigma2 = 1e-10; // noise power
        public double P_peak = 0.1; // 100 mW max transmit power
        public double f_peak = 100e6; // 100 MHz
        public double f_c = 1e10; // 10 GHz edge server CPU
        public double P0 = 1.0; // AP downlink transmit power
        public double kappa = 1e-26; // computing efficiency
    }

    public static class WirelessDeviceWrapper {
        public IoTDevice device;
        public List<Task> tasks;
        public double[] h;
        public double[] g;
        public double beta_E = 0.5; // energy weight
        public double beta_T = 0.5; // time weight

        public WirelessDeviceWrapper(IoTDevice device, List<Task> tasks, double[] h, double[] g) {
            this.device = device;
            this.tasks = tasks;
            this.h = h;
            this.g = g;
        }
    }

    public static double freeSpaceChannel(double distanceM, double G, double Fc, double PL) {
        double c = 3e8;
        return G * Math.pow((c / (4 * Math.PI * Fc * distanceM)), PL);
    }

    public static double lambertW0(double z) {
        if (z < -1.0 / Math.E) {
            return Double.NaN;
        }
        if (z == 0.0)
            return 0.0;

        double w;
        if (z < Math.E) {
            w = 0.0;
        } else {
            w = Math.log(z) - Math.log(Math.log(z));
        }

        for (int i = 0; i < 100; i++) {
            double ew = Math.exp(w);
            double wEw = w * ew;
            double f = wEw - z;
            if (Math.abs(f) < 1e-9)
                break;

            double ew1 = ew * (w + 1);
            double step = f / (ew1 - ((w + 2) * f) / (2 * (w + 1)));
            w -= step;
        }
        return w;
    }

    private static double localExecTime(Task task, double f) {
        return task.getComputationalLoad() / f;
    }

    private static double localExecEnergy(Task task, double f, double kappa) {
        return kappa * task.getComputationalLoad() * f * f;
    }

    private static double uplinkRate(double p, double h, MECSystemParams sys) {
        return sys.W * (Math.log(1 + p * h / sys.sigma2) / Math.log(2));
    }

    private static double uplinkTime(double dataBits, double p, double h, MECSystemParams sys) {
        double R = uplinkRate(p, h, sys);
        return R > 0 ? dataBits / R : Double.POSITIVE_INFINITY;
    }

    private static double uplinkEnergy(double dataBits, double p, double h, MECSystemParams sys) {
        return p * uplinkTime(dataBits, p, h, sys);
    }

    private static double downlinkTime(double dataBits, double g, MECSystemParams sys) {
        double Rd = sys.W * (Math.log(1 + sys.P0 * g / sys.sigma2) / Math.log(2));
        return Rd > 0 ? dataBits / Rd : Double.POSITIVE_INFINITY;
    }

    private static double edgeExecTime(Task task, MECSystemParams sys) {
        return task.getComputationalLoad() / sys.f_c;
    }

    private static double lambertPower(double h, double coeffWeight, double betaE, MECSystemParams sys) {
        if (h <= 0)
            return sys.P_peak;

        double ratio = coeffWeight / betaE;
        double A = 1 + ratio / sys.P_peak;
        double B = h * ratio / sys.sigma2 - 1;

        double threshold_h = sys.sigma2 / sys.P_peak * (A / (-lambertW0(-A * Math.exp(-A))) - 1);

        if (h < threshold_h || B <= 0) {
            return sys.P_peak;
        } else {
            double Bw = lambertW0(B * Math.exp(-1));
            if (Bw <= 0)
                return sys.P_peak;
            double p = sys.sigma2 / h * (B / Bw - 1);
            return Math.max(0, Math.min(p, sys.P_peak));
        }
    }

    public static class Resources {
        public double[] f1;
        public double[] p1;
        public double p1_relay;
        public double[] f2;
        public double[] p2;
    }

    private static Resources computeResources(int[] a1, int[] a2, WirelessDeviceWrapper wd1, WirelessDeviceWrapper wd2,
            int k, MECSystemParams sys, double nu) {
        double lam = nu;
        double mu = wd2.beta_T - nu;
        int M = wd1.tasks.size();
        int N = wd2.tasks.size();

        double f1Val = Math.pow(Math.max((wd1.beta_T + lam) / (2 * sys.kappa * wd1.beta_E), 0), 1.0 / 3.0);
        f1Val = Math.min(f1Val, sys.f_peak);

        double f2PreVal = Math.pow(Math.max(mu / (2 * sys.kappa * wd2.beta_E), 0), 1.0 / 3.0);
        f2PreVal = Math.min(f2PreVal, sys.f_peak);

        double f2PostVal = Math.pow((wd2.beta_T / (2 * sys.kappa * wd2.beta_E)), 1.0 / 3.0);
        f2PostVal = Math.min(f2PostVal, sys.f_peak);

        Resources res = new Resources();
        res.f1 = new double[M];
        for (int i = 0; i < M; i++)
            res.f1[i] = a1[i] == 0 ? f1Val : 0.0;

        res.f2 = new double[N];
        for (int i = 0; i < N; i++) {
            if (a2[i] == 0) {
                res.f2[i] = i < k ? f2PreVal : f2PostVal;
            } else {
                res.f2[i] = 0.0;
            }
        }

        res.p1 = new double[M];
        for (int i = 0; i < M; i++) {
            if (a1[i] == 1) {
                res.p1[i] = lambertPower(wd1.h[i], wd1.beta_T + lam, wd1.beta_E, sys);
            } else {
                res.p1[i] = 0.0;
            }
        }
        res.p1_relay = lambertPower(wd1.h[M], lam, wd1.beta_E, sys);

        res.p2 = new double[N];
        for (int i = 0; i < N; i++) {
            if (a2[i] == 1) {
                if (i <= k) {
                    res.p2[i] = lambertPower(wd2.h[i], mu, wd2.beta_E, sys);
                } else {
                    res.p2[i] = lambertPower(wd2.h[i], wd2.beta_T, wd2.beta_E, sys);
                }
            } else {
                res.p2[i] = 0.0;
            }
        }
        return res;
    }

    private static double computeTWait1(int[] a1, int[] a2, Resources res, WirelessDeviceWrapper wd1,
            WirelessDeviceWrapper wd2, int k, MECSystemParams sys) {
        int M = wd1.tasks.size();
        double total = 0.0;

        for (int i = 0; i < M; i++) {
            Task task = wd1.tasks.get(i);
            if (a1[i] == 0) {
                total += localExecTime(task, Math.max(res.f1[i], 1e-12));
            } else {
                total += edgeExecTime(task, sys);
                if (i == 0 || a1[i - 1] == 0) {
                    total += uplinkTime(task.getEntryDataSize(), res.p1[i], wd1.h[i], sys);
                }
                if (i < M - 1 && a1[i + 1] == 0) {
                    total += downlinkTime(wd1.tasks.get(i + 1).getEntryDataSize(), wd1.g[i + 1], sys);
                }
            }
        }

        double lastOut = wd1.tasks.get(M - 1).getReturnDataSize();
        if (a1[M - 1] == 0) {
            total += uplinkTime(lastOut, res.p1_relay, wd1.h[M], sys);
            if (a2[k] == 0) {
                total += downlinkTime(lastOut, wd2.g[k], sys);
            }
        } else {
            if (a2[k] == 0) {
                total += downlinkTime(lastOut, wd2.g[k], sys);
            }
        }
        return total;
    }

    private static double computeTWait2(int[] a2, Resources res, WirelessDeviceWrapper wd2, int k,
            MECSystemParams sys) {
        double total = 0.0;
        for (int i = 0; i < k; i++) {
            Task task = wd2.tasks.get(i);
            if (a2[i] == 0) {
                total += localExecTime(task, Math.max(res.f2[i], 1e-12));
            } else {
                total += edgeExecTime(task, sys);
                if (i == 0 || a2[i - 1] == 0) {
                    total += uplinkTime(task.getEntryDataSize(), res.p2[i], wd2.h[i], sys);
                }
                if (i < k - 1 && a2[i + 1] == 0) {
                    total += downlinkTime(wd2.tasks.get(i + 1).getEntryDataSize(), wd2.g[i + 1], sys);
                }
            }
        }
        return total;
    }

    public static class Metrics {
        public double T;
        public double E;
    }

    private static Metrics computeWD1Metrics(int[] a1, Resources res, WirelessDeviceWrapper wd1, MECSystemParams sys) {
        int M = wd1.tasks.size();
        double T_comp = 0.0;
        double T_tran = 0.0;
        double E = 0.0;

        for (int i = 0; i < M; i++) {
            Task task = wd1.tasks.get(i);
            if (a1[i] == 0) {
                double tau_l = localExecTime(task, Math.max(res.f1[i], 1e-12));
                T_comp += tau_l;
                E += localExecEnergy(task, Math.max(res.f1[i], 1e-12), sys.kappa);
            } else {
                T_comp += edgeExecTime(task, sys);
                if (i == 0 || a1[i - 1] == 0) {
                    double tau_u = uplinkTime(task.getEntryDataSize(), res.p1[i], wd1.h[i], sys);
                    T_tran += tau_u;
                    E += res.p1[i] * tau_u;
                }
                if (i < M - 1 && a1[i + 1] == 0) {
                    T_tran += downlinkTime(wd1.tasks.get(i + 1).getEntryDataSize(), wd1.g[i + 1], sys);
                }
            }
        }

        if (a1[M - 1] == 0) {
            double tau_relay = uplinkTime(wd1.tasks.get(M - 1).getReturnDataSize(), res.p1_relay, wd1.h[M], sys);
            T_tran += tau_relay;
            E += res.p1_relay * tau_relay;
        }

        Metrics m = new Metrics();
        m.T = T_comp + T_tran;
        m.E = E;
        return m;
    }

    private static Metrics computeWD2Metrics(int[] a1, int[] a2, Resources res, WirelessDeviceWrapper wd1,
            WirelessDeviceWrapper wd2, int k, MECSystemParams sys) {
        int N = wd2.tasks.size();
        double T_wait_1 = computeTWait1(a1, a2, res, wd1, wd2, k, sys);
        double T_wait_2 = computeTWait2(a2, res, wd2, k, sys);
        double T_wait = Math.max(T_wait_1, T_wait_2);

        double T_rest = 0.0;
        double E = 0.0;
        for (int i = k; i < N; i++) {
            Task task = wd2.tasks.get(i);
            if (a2[i] == 0) {
                double f_i = Math.max(res.f2[i], 1e-12);
                T_rest += localExecTime(task, f_i);
                E += localExecEnergy(task, f_i, sys.kappa);
            } else {
                T_rest += edgeExecTime(task, sys);
                if (i == k || a2[i - 1] == 0) {
                    double tau_u = uplinkTime(task.getEntryDataSize(), res.p2[i], wd2.h[i], sys);
                    T_rest += tau_u;
                    E += res.p2[i] * tau_u;
                }
                if (i < N - 1 && a2[i + 1] == 0) {
                    T_rest += downlinkTime(wd2.tasks.get(i + 1).getEntryDataSize(), wd2.g[i + 1], sys);
                }
            }
        }

        for (int i = 0; i < k; i++) {
            Task task = wd2.tasks.get(i);
            if (a2[i] == 0) {
                E += localExecEnergy(task, Math.max(res.f2[i], 1e-12), sys.kappa);
            } else if (i == 0 || a2[i - 1] == 0) {
                E += res.p2[i] * uplinkTime(task.getEntryDataSize(), res.p2[i], wd2.h[i], sys);
            }
        }

        Metrics m = new Metrics();
        m.T = T_wait + T_rest;
        m.E = E;
        return m;
    }

    public static class ObjectiveResult {
        public double totalETC;
        public double nu;
        public Resources res;
    }

    private static ObjectiveResult computeObjective(int[] a1, int[] a2, WirelessDeviceWrapper wd1,
            WirelessDeviceWrapper wd2, int k, MECSystemParams sys, Double nuOpt) {
        double nu = nuOpt != null ? nuOpt : bisectionSearch(a1, a2, wd1, wd2, k, sys, 1e-4);
        Resources res = computeResources(a1, a2, wd1, wd2, k, sys, nu);

        Metrics m1 = computeWD1Metrics(a1, res, wd1, sys);
        Metrics m2 = computeWD2Metrics(a1, a2, res, wd1, wd2, k, sys);

        double eta1 = wd1.beta_E * m1.E + wd1.beta_T * m1.T;
        double eta2 = wd2.beta_E * m2.E + wd2.beta_T * m2.T;

        ObjectiveResult result = new ObjectiveResult();
        result.totalETC = eta1 + eta2;
        result.nu = nu;
        result.res = res;
        return result;
    }

    private static double bisectionSearch(int[] a1, int[] a2, WirelessDeviceWrapper wd1, WirelessDeviceWrapper wd2,
            int k, MECSystemParams sys, double eps) {
        double nu_LB = 0.0;
        double nu_UB = wd2.beta_T - 1e-9;

        java.util.function.Function<Double, Double> psi = (nu) -> {
            Resources res = computeResources(a1, a2, wd1, wd2, k, sys, nu);
            double tw1 = computeTWait1(a1, a2, res, wd1, wd2, k, sys);
            double tw2 = computeTWait2(a2, res, wd2, k, sys);
            return tw1 - tw2;
        };

        if (psi.apply(nu_LB) < 0)
            return nu_LB;
        if (psi.apply(nu_UB) > 0)
            return nu_UB;

        for (int i = 0; i < 60; i++) {
            double nu_mid = (nu_LB + nu_UB) / 2.0;
            double val = psi.apply(nu_mid);
            if (Math.abs(val) < eps)
                return nu_mid;
            if (val > 0)
                nu_LB = nu_mid;
            else
                nu_UB = nu_mid;
        }
        return (nu_LB + nu_UB) / 2.0;
    }

    private static boolean isOneClimb(int[] a) {
        if (a == null || a.length == 0)
            return true;
        // One-climb: [0,0,...,0,1,1,...,1] — once a 1 appears, ALL remaining must be 1
        boolean seenEdge = false;
        for (int bit : a) {
            if (bit == 1) {
                seenEdge = true;
            } else {
                if (seenEdge)
                    return false; // saw a 0 after a 1 — not one-climb
            }
        }
        return true;
    }

    private static List<int[]> generateNeighbors(int[] a) {
        List<int[]> neighbors = new ArrayList<>();
        for (int i = 0; i < a.length; i++) {
            int[] candidate = Arrays.copyOf(a, a.length);
            candidate[i] = 1 - candidate[i];
            if (isOneClimb(candidate)) {
                neighbors.add(candidate);
            }
        }
        return neighbors;
    }

    public static class GibbsResult {
        public int[] a1;
        public int[] a2;
        public double bestObj;
        public Resources res;
    }

    public static GibbsResult gibbsSampling(WirelessDeviceWrapper wd1, WirelessDeviceWrapper wd2, int k,
            MECSystemParams sys, double T_init, double alpha, int maxIter, double tol, long seed) {
        Random rng = new Random(seed);
        int M = wd1.tasks.size();
        int N = wd2.tasks.size();

        int[] a1 = new int[M];
        int[] a2 = new int[N];

        double T_temp = T_init;
        double bestObj = Double.POSITIVE_INFINITY;
        int[] bestA1 = Arrays.copyOf(a1, M);
        int[] bestA2 = Arrays.copyOf(a2, N);
        Resources bestRes = null;

        List<Double> history = new ArrayList<>();

        for (int theta = 0; theta < maxIter; theta++) {
            // WD1 Update
            List<int[]> candidates1 = generateNeighbors(a1);
            candidates1.add(Arrays.copyOf(a1, M));
            double[] scores1 = new double[candidates1.size()];
            double Z1 = 0;
            for (int i = 0; i < candidates1.size(); i++) {
                ObjectiveResult objRes = computeObjective(candidates1.get(i), a2, wd1, wd2, k, sys, null);
                double val = Math.exp(-objRes.totalETC / T_temp);
                if (Double.isNaN(val) || Double.isInfinite(val))
                    val = 0.0;
                scores1[i] = val;
                Z1 += val;
            }
            if (Z1 > 0) {
                double randVal = rng.nextDouble() * Z1;
                double cum = 0;
                for (int i = 0; i < candidates1.size(); i++) {
                    cum += scores1[i];
                    if (randVal <= cum) {
                        a1 = candidates1.get(i);
                        break;
                    }
                }
            }

            // WD2 Update
            List<int[]> candidates2 = generateNeighbors(a2);
            candidates2.add(Arrays.copyOf(a2, N));
            double[] scores2 = new double[candidates2.size()];
            double Z2 = 0;
            for (int i = 0; i < candidates2.size(); i++) {
                ObjectiveResult objRes = computeObjective(a1, candidates2.get(i), wd1, wd2, k, sys, null);
                double val = Math.exp(-objRes.totalETC / T_temp);
                if (Double.isNaN(val) || Double.isInfinite(val))
                    val = 0.0;
                scores2[i] = val;
                Z2 += val;
            }
            if (Z2 > 0) {
                double randVal = rng.nextDouble() * Z2;
                double cum = 0;
                for (int i = 0; i < candidates2.size(); i++) {
                    cum += scores2[i];
                    if (randVal <= cum) {
                        a2 = candidates2.get(i);
                        break;
                    }
                }
            }

            ObjectiveResult currRes = computeObjective(a1, a2, wd1, wd2, k, sys, null);
            if (currRes.totalETC < bestObj) {
                bestObj = currRes.totalETC;
                bestA1 = Arrays.copyOf(a1, M);
                bestA2 = Arrays.copyOf(a2, N);
                bestRes = currRes.res;
            }

            history.add(currRes.totalETC);
            T_temp *= alpha;

            if (theta > 50 && Math.abs(history.get(history.size() - 1) - history.get(history.size() - 2)) < tol) {
                break;
            }
        }

        GibbsResult r = new GibbsResult();
        r.a1 = bestA1;
        r.a2 = bestA2;
        r.bestObj = bestObj;
        r.res = bestRes;
        return r;
    }
}
