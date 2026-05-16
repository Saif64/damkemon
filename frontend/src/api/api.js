import axios from 'axios';

const api = axios.create({
  baseURL: '/api',
  timeout: 15000,
  headers: {
    'Content-Type': 'application/json',
  },
});

api.interceptors.response.use(
  (response) => response,
  (error) => {
    console.error('API Error:', error.response?.data || error.message);
    return Promise.reject(error);
  }
);

export const searchProducts = (query, page = 0, size = 20) =>
  api.get('/search', { params: { q: query, page, size } });

export const getProduct = (id) =>
  api.get(`/products/${id}`);

export const getProductHistory = (id) =>
  api.get(`/products/${id}/history`);

export const getProductReviews = (id) =>
  api.get(`/products/${id}/reviews`);

export const getSites = () =>
  api.get('/sites');

export const getDashboardStats = () =>
  api.get('/dashboard/stats');

export const triggerScrape = (query, sites) =>
  api.post('/scrape', { query, sites });

export const getAllProducts = (page = 0, size = 20) =>
  api.get('/products', { params: { page, size } });

export const compareProducts = (ids) =>
  api.get('/compare', { params: { ids: Array.isArray(ids) ? ids.join(',') : ids } });

export const getSellers = (params = {}) =>
  api.get('/sellers', { params });

export const getSeller = (id) =>
  api.get(`/sellers/${id}`);

export default api;
