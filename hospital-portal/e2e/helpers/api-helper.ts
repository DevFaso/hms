/**
 * API Helper – Direct HTTP calls to the backend for test data setup/teardown.
 *
 * Uses Playwright's APIRequestContext to call the Spring Boot API directly,
 * bypassing the Angular frontend, for creating test fixtures efficiently.
 */
import type { APIRequestContext } from '@playwright/test';

const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8081/api';

export class ApiHelper {
  private token = '';

  constructor(private readonly request: APIRequestContext) {}

  /** Authenticate and store JWT for subsequent calls */
  async authenticate(username: string, password: string): Promise<string> {
    const res = await this.request.post(`${API_BASE}/auth/login`, {
      data: { username, password },
    });
    if (!res.ok()) {
      throw new Error(`Auth failed for ${username}: ${res.status()} ${await res.text()}`);
    }
    const body = await res.json();
    this.token = body.token ?? body.accessToken ?? body.jwt ?? '';
    return this.token;
  }

  private headers(): Record<string, string> {
    const h: Record<string, string> = { 'Content-Type': 'application/json' };
    if (this.token) h['Authorization'] = `Bearer ${this.token}`;
    return h;
  }

  /* ────── Organizations ────── */
  async createOrganization(data: Record<string, unknown>): Promise<Record<string, unknown>> {
    const res = await this.request.post(`${API_BASE}/organizations`, {
      headers: this.headers(),
      data,
    });
    return res.json();
  }

  async listOrganizations(): Promise<Record<string, unknown>> {
    const res = await this.request.get(`${API_BASE}/organizations`, {
      headers: this.headers(),
    });
    return res.json();
  }

  /* ────── Hospitals ────── */
  async createHospital(data: Record<string, unknown>): Promise<Record<string, unknown>> {
    const res = await this.request.post(`${API_BASE}/hospitals`, {
      headers: this.headers(),
      data,
    });
    return res.json();
  }

  async listHospitals(): Promise<Record<string, unknown>> {
    const res = await this.request.get(`${API_BASE}/hospitals`, {
      headers: this.headers(),
    });
    return res.json();
  }

  /* ────── Departments ────── */
  async createDepartment(
    hospitalId: string,
    data: Record<string, unknown>,
  ): Promise<Record<string, unknown>> {
    const res = await this.request.post(`${API_BASE}/hospitals/${hospitalId}/departments`, {
      headers: this.headers(),
      data,
    });
    return res.json();
  }

  async listDepartments(hospitalId: string): Promise<Record<string, unknown>> {
    const res = await this.request.get(`${API_BASE}/hospitals/${hospitalId}/departments`, {
      headers: this.headers(),
    });
    return res.json();
  }

  /* ────── Patients ────── */
  async createPatient(data: Record<string, unknown>): Promise<Record<string, unknown>> {
    const res = await this.request.post(`${API_BASE}/patients`, {
      headers: this.headers(),
      data,
    });
    return res.json();
  }

  async listPatients(params?: Record<string, string>): Promise<Record<string, unknown>> {
    const query = params ? '?' + new URLSearchParams(params).toString() : '';
    const res = await this.request.get(`${API_BASE}/patients${query}`, {
      headers: this.headers(),
    });
    return res.json();
  }

  async deletePatient(id: string): Promise<void> {
    await this.request.delete(`${API_BASE}/patients/${id}`, {
      headers: this.headers(),
    });
  }

  /* ────── Staff ────── */
  async listStaff(params?: Record<string, string>): Promise<Record<string, unknown>> {
    const query = params ? '?' + new URLSearchParams(params).toString() : '';
    const res = await this.request.get(`${API_BASE}/staff${query}`, {
      headers: this.headers(),
    });
    return res.json();
  }

  /* ────── Users ────── */
  async createUser(data: Record<string, unknown>): Promise<Record<string, unknown>> {
    const res = await this.request.post(`${API_BASE}/users`, {
      headers: this.headers(),
      data,
    });
    return res.json();
  }

  async listUsers(params?: Record<string, string>): Promise<Record<string, unknown>> {
    const query = params ? '?' + new URLSearchParams(params).toString() : '';
    const res = await this.request.get(`${API_BASE}/users${query}`, {
      headers: this.headers(),
    });
    return res.json();
  }

  /* ────── Appointments ────── */
  async createAppointment(data: Record<string, unknown>): Promise<Record<string, unknown>> {
    const res = await this.request.post(`${API_BASE}/appointments`, {
      headers: this.headers(),
      data,
    });
    return res.json();
  }

  async listAppointments(params?: Record<string, string>): Promise<Record<string, unknown>> {
    const query = params ? '?' + new URLSearchParams(params).toString() : '';
    const res = await this.request.get(`${API_BASE}/appointments${query}`, {
      headers: this.headers(),
    });
    return res.json();
  }

  /* ────── Billing ────── */
  async listInvoices(params?: Record<string, string>): Promise<Record<string, unknown>> {
    const query = params ? '?' + new URLSearchParams(params).toString() : '';
    const res = await this.request.get(`${API_BASE}/billing/invoices${query}`, {
      headers: this.headers(),
    });
    return res.json();
  }

  /* ────── Roles ────── */
  async listRoles(): Promise<Record<string, unknown>> {
    const res = await this.request.get(`${API_BASE}/roles`, {
      headers: this.headers(),
    });
    return res.json();
  }

  /* ────── Encounters ────── */
  async createEncounter(data: Record<string, unknown>): Promise<Record<string, unknown>> {
    const res = await this.request.post(`${API_BASE}/encounters`, {
      headers: this.headers(),
      data,
    });
    return res.json();
  }

  /* ────── Lab ────── */
  async listLabOrders(params?: Record<string, string>): Promise<Record<string, unknown>> {
    const query = params ? '?' + new URLSearchParams(params).toString() : '';
    const res = await this.request.get(`${API_BASE}/lab/orders${query}`, {
      headers: this.headers(),
    });
    return res.json();
  }

  /* ────── Dashboard ────── */
  async getDashboardSummary(): Promise<Record<string, unknown>> {
    const res = await this.request.get(`${API_BASE}/dashboard/admin-summary`, {
      headers: this.headers(),
    });
    return res.json();
  }

  /* ────── Health Check ────── */
  async healthCheck(): Promise<boolean> {
    try {
      const res = await this.request.get(`${API_BASE}/actuator/health`);
      return res.ok();
    } catch {
      return false;
    }
  }

  /* ────── Generic ────── */
  async get(path: string): Promise<Record<string, unknown>> {
    const res = await this.request.get(`${API_BASE}${path}`, {
      headers: this.headers(),
    });
    return res.json();
  }

  async post(path: string, data: Record<string, unknown>): Promise<Record<string, unknown>> {
    const res = await this.request.post(`${API_BASE}${path}`, {
      headers: this.headers(),
      data,
    });
    return res.json();
  }

  async put(path: string, data: Record<string, unknown>): Promise<Record<string, unknown>> {
    const res = await this.request.put(`${API_BASE}${path}`, {
      headers: this.headers(),
      data,
    });
    return res.json();
  }

  async delete(path: string): Promise<void> {
    await this.request.delete(`${API_BASE}${path}`, {
      headers: this.headers(),
    });
  }
}
